package org.example.groceryguru.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local PostgreSQL cache of Cjenoteka products. Scraped once by search term,
 * then image matching runs entirely in SQL via pg_trgm (see LocalImageMatcherService).
 */
@Service
public class CjenetokaCacheService {

    private static final Logger log = LoggerFactory.getLogger(CjenetokaCacheService.class);
    private static final String CJENOTEKA_SEARCH = "https://cjenoteka.hr/pretraga/?s=";
    private static final Pattern NEXT_DATA = Pattern.compile(
            "__NEXT_DATA__[^>]*>(\\{.*?})</script>", Pattern.DOTALL);

    private final JdbcTemplate jdbc;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    // Live progress
    private final AtomicInteger cacheTotal = new AtomicInteger(0);
    private final AtomicInteger cacheDone  = new AtomicInteger(0);
    private final AtomicInteger cacheFound = new AtomicInteger(0);
    private volatile String cachePhase = "idle";

    public CjenetokaCacheService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // --- Public API ---
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("phase", cachePhase);
        stats.put("searchTermsProcessed", cacheDone.get());
        stats.put("searchTermsTotal", cacheTotal.get());
        stats.put("newProductsFoundThisRun", cacheFound.get());
        try {
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM cjenoteka_products", Long.class);
            Long withBarcodes = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cjenoteka_products " +
                    "WHERE barcodes IS NOT NULL AND array_length(barcodes, 1) > 0", Long.class);
            stats.put("cachedProducts", total);
            stats.put("cachedWithBarcodes", withBarcodes);
        } catch (Exception e) {
            stats.put("cachedProducts", "table not yet created");
        }
        return stats;
    }

    /**
     * Scrapes Cjenoteka for each distinct category and brand in our products table,
     * storing ALL found products into {@code cjenoteka_products}.
     * Rate-limited at 1 request/second.
     * Must be called from a background thread - this is a long-running operation.
     */
    public void buildCache() {
        setupSchema();

        List<String> terms = gatherSearchTerms();

        log.info("Building Cjenoteka cache: {} search terms at 1 req/sec", terms.size());
        cachePhase = "building";
        cacheTotal.set(terms.size());
        cacheDone.set(0);
        cacheFound.set(0);

        for (String term : terms) {
            try {
                int found = scrapeAndCache(term);
                cacheFound.addAndGet(found);
                int done = cacheDone.incrementAndGet();
                if (done % 20 == 0 || done == terms.size()) {
                    log.info("Cache build: {}/{} terms - {} products found this run (total in DB: {})",
                            done, terms.size(), cacheFound.get(), dbCount());
                }
                Thread.sleep(1000); // Cjenoteka rate limit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Cache build interrupted after {}/{} terms", cacheDone.get(), terms.size());
                break;
            } catch (Exception e) {
                cacheDone.incrementAndGet();
                log.debug("Error scraping term '{}': {}", term, e.getMessage());
            }
        }

        log.info("Cjenoteka cache build complete: {} products in DB", dbCount());
        cachePhase = "idle";
    }

    /**
     * Search terms come from three sources: categories, the top 500 brands, and
     * the first word of every product still missing an image ("MAHUNA 500g" ->
     * "mahuna"). The last group is what targets the coverage gap directly.
     */
    private List<String> gatherSearchTerms() {
        List<String> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        jdbc.query("SELECT DISTINCT category FROM products " +
                   "WHERE category IS NOT NULL AND category != '' ORDER BY category",
                (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> addTerm(terms, seen, rs.getString("category")));

        jdbc.query("SELECT DISTINCT brand FROM products " +
                   "WHERE brand IS NOT NULL AND brand != '' ORDER BY brand LIMIT 500",
                (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> addTerm(terms, seen, rs.getString("brand")));

        // first word of every product still missing an image
        jdbc.query(
                "SELECT DISTINCT lower(split_part(name, ' ', 1)) AS w " +
                "FROM products WHERE image_url IS NULL AND name IS NOT NULL AND name != ''",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String w = rs.getString("w");
                    if (isUsefulTerm(w)) addTerm(terms, seen, w);
                });

        return terms;
    }

    private void addTerm(List<String> terms, Set<String> seen, String raw) {
        if (raw == null) return;
        String t = raw.trim();
        if (t.isEmpty()) return;
        String key = t.toLowerCase();
        if (seen.add(key)) terms.add(t);
    }

    /** Useful terms are >=3 chars and not pure quantity/number tokens (500g, 250). */
    private boolean isUsefulTerm(String w) {
        if (w == null) return false;
        w = w.trim();
        if (w.length() < 3) return false;
        // 500g, 1l, 250, 2kg, 100ml ... - no value as a search term
        return !w.matches("\\d+[a-z]{0,3}");
    }

    // --- Scraping ---
    /**
     * Fetches one Cjenoteka search page for {@code term}, parses ALL product nodes
     * from __NEXT_DATA__, and batch-inserts them into {@code cjenoteka_products}.
     *
     * @return number of NEW rows inserted (existing rows skipped by ON CONFLICT DO NOTHING)
     */
    private int scrapeAndCache(String term) throws Exception {
        String url = CJENOTEKA_SEARCH + URLEncoder.encode(term, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return 0;

        Matcher nd = NEXT_DATA.matcher(resp.body());
        if (!nd.find()) return 0;

        JsonNode root = json.readTree(nd.group(1));
        List<CjenotekaProduct> products = new ArrayList<>();
        collectProducts(root, products);
        if (products.isEmpty()) return 0;

        String sql = "INSERT INTO cjenoteka_products (name, image_url, barcodes, category) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT (image_url) DO UPDATE " +
                     "SET barcodes = array(SELECT DISTINCT unnest(" +
                     "    COALESCE(cjenoteka_products.barcodes,'{}') || " +
                     "    COALESCE(EXCLUDED.barcodes,'{}'))), " +
                     "    scraped_at = NOW()";

        jdbc.batchUpdate(sql, products, 500, (ps, p) -> {
            ps.setString(1, p.name());
            ps.setString(2, p.imageUrl());
            if (!p.barcodes().isEmpty()) {
                Array arr = ps.getConnection()
                        .createArrayOf("text", p.barcodes().toArray(new String[0]));
                ps.setArray(3, arr);
            } else {
                ps.setNull(3, java.sql.Types.ARRAY);
            }
            ps.setString(4, term);
        });

        // Count actual inserts (ON CONFLICT UPDATE still returns 1, but new rows matter more)
        return products.size();
    }

    /**
     * Recursively walks Cjenoteka __NEXT_DATA__ JSON and collects every node
     * that has both a {@code thumb} (product image) and a {@code name}/{@code title}.
     * Skips category/shop thumbnails - only real product images are kept.
     */
    private void collectProducts(JsonNode node, List<CjenotekaProduct> out) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("thumb") && (node.has("name") || node.has("title"))) {
                String thumb = node.path("thumb").asText("");
                String name  = node.path("name").asText("");
                if (name.isBlank()) name = node.path("title").asText("");
                if (!thumb.isBlank() && !name.isBlank() && isProductImage(thumb)) {
                    List<String> barcodes = new ArrayList<>();
                    JsonNode bc = node.path("barcodes");
                    if (bc.isArray()) {
                        for (JsonNode b : bc) {
                            String s = b.asText("").trim();
                            if (!s.isBlank()) barcodes.add(s);
                        }
                    }
                    out.add(new CjenotekaProduct(name, thumb, barcodes));
                }
            }
            var it = node.fields();
            while (it.hasNext()) collectProducts(it.next().getValue(), out);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectProducts(child, out);
        }
    }

    // --- Helpers ---
    private boolean isProductImage(String url) {
        if (url == null) return false;
        if (url.contains("category_thumb") || url.contains("shop_thumb")) return false;
        return url.contains("product_small") || url.contains("product_medium")
                || url.contains("product_large");
    }

    private long dbCount() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cjenoteka_products", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Creates the {@code cjenoteka_products} table, enables pg_trgm extension,
     * and creates the GIN indexes needed for efficient barcode lookup and
     * trigram similarity joins. Safe to call multiple times.
     */
    public void setupSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cjenoteka_products (
                id         BIGSERIAL PRIMARY KEY,
                name       TEXT NOT NULL,
                image_url  TEXT NOT NULL UNIQUE,
                barcodes   TEXT[],
                category   TEXT,
                scraped_at TIMESTAMP DEFAULT NOW()
            )""");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cjp_barcodes " +
                     "ON cjenoteka_products USING GIN(barcodes)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cjp_name_trgm " +
                     "ON cjenoteka_products USING GIN(lower(name) gin_trgm_ops)");
        log.info("Cjenoteka cache schema ready");
    }

    // --- Internal record ---
    private record CjenotekaProduct(String name, String imageUrl, List<String> barcodes) {}
}

package org.example.groceryguru.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.repository.ProductRepo;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches product images from multiple Croatian grocery sources:
 * 1. Cjenoteka (barcode search)
 * 2. Tommy (EAN filter, Sylius API)
 * 3. Lidl (name search, JSON API)
 * 4. Konzum (name search, HTML scrape)
 * 5. Spar (name search, HTML scrape)
 * 6. dm (name search, Algolia-style API)
 * 7. Open Food Facts (barcode lookup)
 */
@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    // --- Cjenoteka ---
    private static final String CJENOTEKA_SEARCH = "https://cjenoteka.hr/pretraga/?s=";
    private static final Pattern NEXT_DATA = Pattern.compile(
            "__NEXT_DATA__[^>]*>(\\{.*?)</script>", Pattern.DOTALL);

    // --- Tommy (Sylius API filtered by barcode) ---
    private static final String TOMMY_BY_CODE  = "https://spiza.tommy.hr/api/v2/shop/products?filter[code]=";
    private static final String TOMMY_BY_NAME  = "https://spiza.tommy.hr/api/v2/shop/products?itemsPerPage=3&filter[search]=";

    // --- Lidl ---
    private static final String LIDL_API = "https://www.lidl.hr/q/api/search?assortment=HR&locale=hr_HR&version=v2.0.0&q=";

    // --- Konzum ---
    private static final String KONZUM_SEARCH = "https://www.konzum.hr/web/search?search[term]=";
    private static final Pattern KONZUM_IMAGE = Pattern.compile(
            "<img[^>]+src=\"(https://d17zv3ray5yxvp\\.cloudfront\\.net/variants/[^\"]+)\"");

    // --- Spar ---
    private static final String SPAR_SEARCH = "https://www.spar.hr/online/search?q=";
    private static final Pattern SPAR_IMAGE = Pattern.compile(
            "<img[^>]+src=\"(https://[^\"]*spar[^\"]*/dam/[^\"]+)\"");

    // --- dm ---
    private static final String DM_SEARCH =
            "https://product-search.services.dmtech.com/hr/search?query=";
    private static final Pattern DM_IMAGE = Pattern.compile(
            "\"imageUrlTemplate\"\\s*:\\s*\"([^\"]+)\"");

    // --- Open Food Facts ---
    private static final String OFF_API = "https://world.openfoodfacts.org/api/v2/product/";
    private static final Pattern OFF_IMAGE = Pattern.compile(
            "\"image_front_(?:small_)?url\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Number of worker threads per phase. Each thread processes its chunk of products
     * sequentially with {@link #REQUEST_DELAY_MS} between requests, so the aggregate
     * rate per source is {@code PARALLEL_THREADS * (1000 / REQUEST_DELAY_MS)}.
     * <p>
     * Default: 40 threads × 50 ms -> 800 req/sec theoretical, bounded in practice by
     * HTTP latency (~150-250 req/sec). Bump threads higher if your bandwidth can
     * take it and you're not getting 429 responses from any source.
     */
    private static final int PARALLEL_THREADS = 40;
    private static final int REQUEST_DELAY_MS = 50;

    private final ProductRepo productRepo;
    private final JdbcTemplate jdbc;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Shared pool used by {@link #fetchFirstNonNull(List, int)} to run every source
     * in a tier in parallel. Sized well above {@link #PARALLEL_THREADS} so that
     * chunk-processor threads never block waiting for an HTTP worker to free up.
     * With 40 chunk threads × up to 4 sources/tier = 160 concurrent futures; 192
     * is the minimum safe size.
     */
    private final ExecutorService httpWorkerPool = Executors.newFixedThreadPool(
            192,
            r -> {
                Thread t = new Thread(r, "img-http-worker");
                t.setDaemon(true);
                return t;
            }
    );

    // --- Live progress tracking ---
    private final AtomicInteger progressTotal = new AtomicInteger(0);
    private final AtomicInteger progressDone = new AtomicInteger(0);
    private final AtomicInteger progressFound = new AtomicInteger(0);
    private volatile String progressPhase = "idle";
    private volatile long progressStartTime = 0;

    public Map<String, Object> getProgress() {
        int total = progressTotal.get();
        int done = progressDone.get();
        int found = progressFound.get();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", progressPhase);
        map.put("processed", done);
        map.put("total", total);
        map.put("found", found);
        map.put("missed", done - found);
        map.put("percentDone", total > 0 ? (done * 100) / total : 0);
        map.put("hitRate", done > 0 ? String.format("%.1f%%", found * 100.0 / done) : "0%");

        if (progressStartTime > 0 && done > 0 && done < total) {
            long elapsed = System.currentTimeMillis() - progressStartTime;
            long etaMs = (elapsed / done) * (long)(total - done);
            map.put("elapsedSeconds", elapsed / 1000);
            map.put("etaSeconds", etaMs / 1000);
        }

        // DB snapshot
        long withImage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE image_url IS NOT NULL", Long.class);
        long withoutImage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE image_url IS NULL", Long.class);
        map.put("dbWithImage", withImage);
        map.put("dbWithoutImage", withoutImage);

        return map;
    }

    public ProductImageService(ProductRepo productRepo, JdbcTemplate jdbc) {
        this.productRepo = productRepo;
        this.jdbc = jdbc;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fires every supplier in parallel on {@link #httpWorkerPool} and returns the
     * first non-null result in preference order. Uses CompletableFuture semantics:
     * <ul>
     *   <li>All suppliers start at the same time - no sequential waiting</li>
     *   <li>Each supplier's exception is swallowed so one failing source doesn't
     *       take down the others</li>
     *   <li>We wait up to {@code timeoutMs} for ALL to finish, then check results
     *       in preference order and return the first non-null</li>
     *   <li>If a source is slower than the timeout, it's dropped silently and its
     *       HTTP connection is allowed to close in the background</li>
     * </ul>
     * Effect: per-product latency collapses from the sum of all source latencies
     * to the {@code max} of them, and load is spread across sources rather than
     * hammering a single one.
     */
    private String fetchFirstNonNull(List<Supplier<String>> suppliers, int timeoutMs) {
        List<CompletableFuture<String>> futures = new ArrayList<>(suppliers.size());
        for (Supplier<String> s : suppliers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return s.get();
                } catch (Exception e) {
                    return null;
                }
            }, httpWorkerPool));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // timeout / interrupt - losing futures keep running in the background
            // but we've already waited long enough; proceed with whatever's done
        }

        // Preference order: return the first non-null result
        for (CompletableFuture<String> f : futures) {
            try {
                String result = f.getNow(null);
                if (result != null) return result;
            } catch (Exception e) { /* ignore */ }
        }
        return null;
    }
    // Source 1: Cjenoteka - barcode search
    private String searchCjenotekaByBarcode(String barcode) {
        try {
            String url = CJENOTEKA_SEARCH + URLEncoder.encode(barcode, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            Matcher nd = NEXT_DATA.matcher(response.body());
            if (!nd.find()) return null;

            String jsonStr = nd.group(1);
            // Fast-fail: the barcode must appear somewhere on the page
            if (!jsonStr.contains(barcode)) return null;

            // Properly parse the __NEXT_DATA__ JSON and walk every product object,
            // returning the thumb of the one whose `barcodes[]` array contains our EAN.
            // Taking the first regex match is WRONG - Cjenoteka's search results page
            // has featured/sponsored products at the top whose thumb would be picked
            // up for every unrelated product that merely happens to land on the page.
            JsonNode root = json.readTree(jsonStr);
            String matched = findCjenotekaThumbByBarcode(root, barcode);
            if (matched != null && isRealProductImage(matched)) return matched;
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /**
     * Recursively walks a Cjenoteka __NEXT_DATA__ tree looking for a node that:
     *   - has a {@code barcodes} array containing the target barcode
     *   - AND has a {@code thumb} string on the same object
     * Returns the thumb URL, or {@code null} if no match is found.
     */
    private String findCjenotekaThumbByBarcode(JsonNode node, String barcode) {
        if (node == null) return null;

        if (node.isObject()) {
            JsonNode barcodes = node.path("barcodes");
            if (barcodes.isArray()) {
                for (JsonNode b : barcodes) {
                    if (barcode.equals(b.asText())) {
                        String thumb = node.path("thumb").asText("");
                        if (!thumb.isBlank()) return thumb;
                    }
                }
            }
            // Recurse into children
            var fields = node.fields();
            while (fields.hasNext()) {
                String hit = findCjenotekaThumbByBarcode(fields.next().getValue(), barcode);
                if (hit != null) return hit;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String hit = findCjenotekaThumbByBarcode(child, barcode);
                if (hit != null) return hit;
            }
        }
        return null;
    }
    // Source 2: Tommy - Sylius API (EAN filter first, name fallback)
    private String searchTommyByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) return null;
        try {
            String url = TOMMY_BY_CODE + URLEncoder.encode(barcode, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            JsonNode root = json.readTree(response.body());

            // Sanity: Tommy's Sylius API sometimes ignores unknown filters and
            // returns the WHOLE catalog. If the total count is suspicious (> 50),
            // the filter clearly wasn't applied - bail out.
            int total = root.path("hydra:totalItems").asInt(-1);
            if (total > 50) return null;

            JsonNode members = root.path("hydra:member");
            if (!members.isArray() || members.isEmpty()) return null;

            for (JsonNode product : members) {
                // REQUIRED: the product's `code` field must exactly equal our barcode.
                // Without this check Tommy can return a default/fallback product for
                // every missed query, which would attach the same wrong image to
                // tens of thousands of unrelated products in our DB.
                String productCode = product.path("code").asText("");
                if (!barcode.equals(productCode)) continue;

                String img = pickTommyImage(product);
                if (img != null) return img;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private String searchTommyByName(String productName) {
        try {
            String query = simplifyName(productName);
            if (query.isBlank()) return null;

            String url = TOMMY_BY_NAME + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            JsonNode root = json.readTree(response.body());

            // Same sanity check as barcode path: huge result count = filter ignored
            int total = root.path("hydra:totalItems").asInt(-1);
            if (total > 500) return null;

            JsonNode members = root.path("hydra:member");
            if (!members.isArray() || members.isEmpty()) return null;

            // Same overlap rule as Lidl: need 2+ matching words (or all if <=2 words).
            Set<String> ourWords = extractWords(productName);
            if (ourWords.isEmpty()) return null;
            int minMatches = Math.min(2, ourWords.size());

            for (JsonNode product : members) {
                String resultName = product.path("name").asText("");
                if (resultName.isBlank()) continue;

                Set<String> theirWords = extractWords(resultName);
                int matches = 0;
                for (String w : ourWords) {
                    if (theirWords.contains(w)) matches++;
                }
                if (matches < minMatches) continue;

                String img = pickTommyImage(product);
                if (img != null) return img;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /** Extracts the best (largest) image URL from a Tommy product JSON node. */
    private String pickTommyImage(JsonNode product) {
        JsonNode images = product.path("images");
        if (!images.isArray() || images.isEmpty()) return null;
        JsonNode variants = images.get(0).path("imageVariants");
        // Prefer LG, then LGx2, SM
        for (String size : new String[]{"LG", "LGx2", "LGx4", "SMx2", "SM"}) {
            String url = variants.path(size).asText("");
            if (!url.isBlank() && url.startsWith("http")) return url;
        }
        return null;
    }
    // Source 3: Lidl - name search via JSON API
    private String searchLidlByName(String productName) {
        try {
            String query = simplifyName(productName);
            if (query.isBlank()) return null;

            String url = LIDL_API + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            String body = response.body();
            if (body.contains("\"type\":\"empty\"")) return null;

            JsonNode root = json.readTree(body);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;

            // Require enough word overlap to avoid Lidl's "popular item" fallback
            // contamination. A single common word like "mlijeko" would match
            // hundreds of unrelated products to the same Lidl item.
            // Rule: need at least 2 matching words, OR all words if name has <=2.
            Set<String> ourWords = extractWords(productName);
            if (ourWords.isEmpty()) return null;
            int minMatches = Math.min(2, ourWords.size()); // 1-word name -> need 1; 2+ -> need 2

            for (JsonNode item : items) {
                JsonNode data = item.path("gridbox").path("data");
                String title = data.path("fullTitle").asText("");
                if (title.isBlank()) continue;

                Set<String> theirWords = extractWords(title);
                int matches = 0;
                for (String w : ourWords) {
                    if (theirWords.contains(w)) matches++;
                }
                if (matches < minMatches) continue;

                String image = data.path("image").asText("");
                if (!image.isEmpty()) return image;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    // Source 4: Konzum - name search via HTML scrape
    private String searchKonzumByName(String productName) {
        // Konzum is safe (no fallback contamination - says "Nismo pronašli" on miss)
        // so we try multiple query variants for higher hit rate
        for (String query : buildSearchQueries(productName)) {
            String result = searchKonzumWithQuery(query);
            if (result != null) return result;
        }
        return null;
    }

    private String searchKonzumWithQuery(String query) {
        try {
            String url = KONZUM_SEARCH + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            String html = response.body();
            if (html.contains("Nismo pronašli")) return null;

            Matcher imgM = KONZUM_IMAGE.matcher(html);
            if (imgM.find()) return imgM.group(1);
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    // Source 5: Spar - name search via HTML scrape
    private String searchSparByName(String productName) {
        // Spar is safe (returns nothing on miss - no fallback contamination)
        for (String query : buildSearchQueries(productName)) {
            String result = searchSparWithQuery(query);
            if (result != null) return result;
        }
        return null;
    }

    private String searchSparWithQuery(String query) {
        try {
            String url = SPAR_SEARCH + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            String html = response.body();
            Matcher m = SPAR_IMAGE.matcher(html);
            if (m.find()) {
                String u = m.group(1);
                if (!u.isBlank() && u.startsWith("http")) return u;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    // Source 6: dm - name search via product-search service
    private String searchDmByName(String productName) {
        try {
            String query = simplifyName(productName);
            if (query.isBlank()) return null;

            String url = DM_SEARCH + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(url);
            if (response == null || response.statusCode() != 200) return null;

            String body = response.body();
            Matcher m = DM_IMAGE.matcher(body);
            if (m.find()) {
                String template = m.group(1);
                // Template looks like: https://media.dm-static.com/.../f_auto,q_auto,w_{width}/...
                String u = template.replace("{width}", "400").replace("{height}", "400");
                if (u.startsWith("http")) return u;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    // Source 7: Open Food Facts - barcode lookup
    private String fetchFromOpenFoodFacts(String barcode) {
        try {
            HttpResponse<String> response = httpGet(
                    OFF_API + barcode + ".json?fields=image_front_url,image_front_small_url");
            if (response == null || response.statusCode() != 200) return null;

            Matcher m = OFF_IMAGE.matcher(response.body());
            if (m.find()) {
                String u = m.group(1);
                if (!u.isBlank() && u.startsWith("http")) return u;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    // Helpers
    private HttpResponse<String> httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Simplify product name for search: strip quantities/units, keep brand + key words.
     * "Z BREGOV trajno mlijeko 2,8% m.m. 1L" -> "Z BREGOV trajno mlijeko"
     * Default: up to 6 meaningful words.
     */
    private String simplifyName(String name) {
        return simplifyName(name, 6);
    }

    private String simplifyName(String name, int maxWords) {
        if (name == null) return "";
        // Remove quantities, percentages, units, multipliers
        String clean = name
                .replaceAll("\\d+[.,]?\\d*\\s*(%|g|kg|l|ml|dl|cl|m\\.m\\.|kom|kn|€)", "")
                .replaceAll("\\d+\\s*x\\s*\\d+", "")
                .replaceAll("\\d+[.,]\\d+", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        String[] words = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String word : words) {
            if (word.length() > 1 && count < maxWords) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(word);
                count++;
            }
        }
        return sb.toString();
    }

    /**
     * Build multiple query variants for a product name, most specific first:
     * 1. Standard simplification (up to 6 words - brand + key descriptors)
     * 2. Short version (first 2 meaningful words - typically brand + category)
     */
    private List<String> buildSearchQueries(String name) {
        List<String> queries = new ArrayList<>();
        String standard = simplifyName(name, 6);
        if (!standard.isBlank()) queries.add(standard);
        String shortQ = simplifyName(name, 2);
        if (!shortQ.isBlank() && !shortQ.equals(standard)) queries.add(shortQ);
        return queries;
    }

    /**
     * Croatian/English stopwords + units that should not count as matching words.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "i", "u", "z", "s", "od", "do", "za", "na", "po", "iz", "the", "and", "or",
            "kom", "kg", "ml", "dl", "cl", "lt", "gr", "kn", "eur", "pak", "pakiranje"
    );

    /**
     * Extract significant words (3+ chars, not stopwords), lowercased + diacritics flattened.
     */
    private Set<String> extractWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) return words;
        String clean = text.toLowerCase()
                .replace('č', 'c').replace('ć', 'c')
                .replace('š', 's').replace('ž', 'z').replace('đ', 'd')
                .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u')
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String word : clean.split(" ")) {
            if (word.length() >= 3 && !STOPWORDS.contains(word)
                    && !word.matches("\\d+[a-z]{0,3}")) {  // skip quantities: 500g, 100ml, 2kg, 250...
                words.add(word);
            }
        }
        return words;
    }
    private boolean isRealProductImage(String url) {
        if (url == null) return false;
        if (url.contains("category_thumb")) return false;
        if (url.contains("shop_thumb")) return false;
        return url.contains("product_small") || url.contains("product_medium")
                || url.contains("product_large")
                || url.contains("openfoodfacts")
                || url.contains("cloudfront.net")
                || url.contains("assets.schwarz");
    }

    // --- Public single-product API ---
    public String fetchImageUrl(String barcode) {
        if (barcode == null || barcode.isBlank()) return null;
        String url = searchCjenotekaByBarcode(barcode);
        if (url != null) return url;
        return fetchFromOpenFoodFacts(barcode);
    }
    // Batch fetch - tier-parallel execution
    /**
     * Per-tier hard timeout for {@link #fetchFirstNonNull(List, int)}. Sources that
     * don't answer within this window are dropped on the floor. Set high enough that
     * well-behaved sources (~300-800 ms) always succeed, low enough that a single
     * stuck source can't drag the whole pipeline.
     */
    private static final int TIER_TIMEOUT_MS = 6000;

    public void fetchAllMissingImages() {
        // Clean up bad images - reset searched flag so they get retried
        int cleaned = jdbc.update(
                "UPDATE products SET image_url = NULL, image_searched = false " +
                "WHERE image_url LIKE '%category_thumb%' OR image_url LIKE '%shop_thumb%'");
        if (cleaned > 0) {
            log.info("Cleaned {} bad images from DB - will retry", cleaned);
        }

        // Load products needing images (not yet searched)
        List<String[]> products = loadUnsearchedProducts();
        long skipped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE image_url IS NULL AND image_searched = true", Long.class);
        log.info("Skipping {} already-searched products. {} NEW products to process.", skipped, products.size());
        if (products.isEmpty()) {
            logFinalStats();
            return;
        }

        // --- Tier-parallel fetch ---
        //
        // For each product:
        //   1. Fire Tier 1 (barcode sources: Cjenoteka + Tommy + OFF) in parallel,
        //      take the first non-null result in preference order.
        //   2. If all Tier 1 sources missed, fire Tier 2 (name sources: Lidl +
        //      Konzum + Spar + dm) in parallel, take the first non-null.
        //   3. Mark the product searched regardless of outcome so the next run
        //      skips it.
        //
        // This compresses the old 7 sequential phases into at most 2 tiers where
        // every source in a tier runs at the same time - cutting per-product
        // latency and naturally spreading load across sources so no single one
        // gets hammered.
        log.info("Tier-parallel image fetch: {} products", products.size());
        AtomicInteger tier1Found = new AtomicInteger(0);
        AtomicInteger tier2Found = new AtomicInteger(0);

        runParallel("ImageFetch", products, p -> {
            final long id = Long.parseLong(p[0]);
            final String barcode = p[1];
            final String name = p[2];

            // Tier 1 - barcode-based sources, run in parallel
            String img = null;
            if (barcode != null && !barcode.isBlank()) {
                img = fetchFirstNonNull(List.of(
                        () -> searchCjenotekaByBarcode(barcode),
                        () -> searchTommyByBarcode(barcode),
                        () -> fetchFromOpenFoodFacts(barcode)
                ), TIER_TIMEOUT_MS);
            }

            if (img != null) {
                jdbc.update(
                        "UPDATE products SET image_url = ?, image_searched = true WHERE id = ?",
                        img, id);
                tier1Found.incrementAndGet();
                return true;
            }

            // Tier 2 - name-based sources, run in parallel
            if (name != null && !name.isBlank()) {
                img = fetchFirstNonNull(List.of(
                        () -> searchLidlByName(name),
                        () -> searchKonzumByName(name),
                        () -> searchSparByName(name),
                        () -> searchDmByName(name)
                ), TIER_TIMEOUT_MS);
            }

            if (img != null) {
                jdbc.update(
                        "UPDATE products SET image_url = ?, image_searched = true WHERE id = ?",
                        img, id);
                tier2Found.incrementAndGet();
                return true;
            }

            // All sources exhausted - mark as searched so next run skips it
            jdbc.update("UPDATE products SET image_searched = true WHERE id = ?", id);
            return false;
        });

        log.info("Tier-parallel fetch done: {} from Tier 1 (barcode), {} from Tier 2 (name)",
                tier1Found.get(), tier2Found.get());
        logFinalStats();
    }
    // Smart home-store routing
    /**
     * Smart image fetcher: Cjenoteka name search first (covers ALL chains),
     * then home store, then barcode/name fallbacks.
     */
    public void fetchImagesByHomeStore() {
        int cleaned = jdbc.update(
                "UPDATE products SET image_url = NULL, image_searched = false " +
                "WHERE image_url LIKE '%category_thumb%' OR image_url LIKE '%shop_thumb%'");
        if (cleaned > 0) {
            log.info("Cleaned {} bad images from DB - will retry", cleaned);
        }

        // Load products with their chain names via prices -> stores -> store_chains
        log.info("Loading products with chain associations...");
        List<String[]> products = new ArrayList<>();
        jdbc.query(
                "SELECT p.id, p.barcode, p.name, " +
                "       STRING_AGG(DISTINCT sc.name, ',' ORDER BY sc.name) AS chains " +
                "FROM products p " +
                "LEFT JOIN prices pr ON pr.product_id = p.id AND pr.is_current = true " +
                "LEFT JOIN stores s ON pr.store_id = s.id " +
                "LEFT JOIN store_chains sc ON s.chain_id = sc.id " +
                "WHERE p.image_url IS NULL AND p.image_searched = false " +
                "GROUP BY p.id, p.barcode, p.name",
                rs -> {
                    products.add(new String[]{
                            String.valueOf(rs.getLong("id")),
                            rs.getString("barcode"),
                            rs.getString("name"),
                            rs.getString("chains")  // nullable, comma-separated
                    });
                });

        if (products.isEmpty()) {
            log.info("No products to process.");
            logFinalStats();
            return;
        }

        // Log chain distribution
        Map<String, Integer> chainDist = new TreeMap<>();
        int noChain = 0;
        for (String[] p : products) {
            if (p[3] == null || p[3].isBlank()) {
                noChain++;
            } else {
                for (String c : p[3].split(",")) {
                    chainDist.merge(c.trim(), 1, Integer::sum);
                }
            }
        }
        log.info("Home-store image fetch: {} products", products.size());
        log.info("Chain distribution: {} - {} with no chain info", chainDist, noChain);

        AtomicInteger homeHits = new AtomicInteger(0);
        AtomicInteger fallbackHits = new AtomicInteger(0);

        runParallel("SmartFetch", products, p -> {
            final long id = Long.parseLong(p[0]);
            final String barcode = p[1];
            final String name = p[2];
            final String chains = p[3];

            String img = null;

            // --- Tier 1: Home store(s) - search the chain that actually sells this ---
            if (chains != null && !chains.isBlank() && name != null && !name.isBlank()) {
                List<Supplier<String>> homeSources = homeSourcesFor(chains, barcode, name);
                if (!homeSources.isEmpty()) {
                    img = fetchFirstNonNull(homeSources, TIER_TIMEOUT_MS);
                }
            }

            if (img != null) {
                jdbc.update("UPDATE products SET image_url = ?, image_searched = true WHERE id = ?", img, id);
                homeHits.incrementAndGet();
                return true;
            }

            // --- Tier 2: Barcode fallback ---
            if (barcode != null && !barcode.isBlank()) {
                Set<String> tried = chainSetLower(chains);
                List<Supplier<String>> barcodeSources = new ArrayList<>();
                if (!tried.contains("tommy"))
                    barcodeSources.add(() -> searchTommyByBarcode(barcode));
                barcodeSources.add(() -> fetchFromOpenFoodFacts(barcode));
                img = fetchFirstNonNull(barcodeSources, TIER_TIMEOUT_MS);
            }

            if (img != null) {
                jdbc.update("UPDATE products SET image_url = ?, image_searched = true WHERE id = ?", img, id);
                fallbackHits.incrementAndGet();
                return true;
            }

            // --- Tier 3: Other name sources not already tried as home store ---
            if (name != null && !name.isBlank()) {
                Set<String> tried = chainSetLower(chains);
                List<Supplier<String>> remaining = new ArrayList<>();
                if (!tried.contains("konzum"))  remaining.add(() -> searchKonzumByName(name));
                if (!tried.contains("spar"))    remaining.add(() -> searchSparByName(name));
                if (!tried.contains("lidl"))    remaining.add(() -> searchLidlByName(name));
                if (!tried.contains("tommy"))   remaining.add(() -> searchTommyByName(name));
                if (!tried.contains("dm"))      remaining.add(() -> searchDmByName(name));
                if (!remaining.isEmpty()) {
                    img = fetchFirstNonNull(remaining, TIER_TIMEOUT_MS);
                }
            }

            if (img != null) {
                jdbc.update("UPDATE products SET image_url = ?, image_searched = true WHERE id = ?", img, id);
                fallbackHits.incrementAndGet();
                return true;
            }

            jdbc.update("UPDATE products SET image_searched = true WHERE id = ?", id);
            return false;
        });

        log.info("Smart fetch done: {} home store, {} fallback",
                homeHits.get(), fallbackHits.get());
        logFinalStats();
    }

    /**
     * Build the list of suppliers targeting the product's home chain(s).
     * Each chain maps to its own scraper method.
     */
    private List<Supplier<String>> homeSourcesFor(String chains, String barcode, String name) {
        List<Supplier<String>> sources = new ArrayList<>();
        for (String chain : chains.split(",")) {
            switch (chain.trim().toLowerCase()) {
                case "konzum"  -> sources.add(() -> searchKonzumByName(name));
                case "spar"    -> sources.add(() -> searchSparByName(name));
                case "lidl"    -> sources.add(() -> searchLidlByName(name));
                case "dm"      -> sources.add(() -> searchDmByName(name));
                case "tommy"   -> {
                    if (barcode != null && !barcode.isBlank())
                        sources.add(() -> searchTommyByBarcode(barcode));
                    sources.add(() -> searchTommyByName(name));
                }
                // Kaufland, Plodine, Eurospin, Studenac, etc. - no scraper yet,
                // these products fall through to Tier 2/3 which tries all sources.
            }
        }
        return sources;
    }

    private Set<String> chainSetLower(String chains) {
        Set<String> set = new HashSet<>();
        if (chains != null) {
            for (String c : chains.split(",")) set.add(c.trim().toLowerCase());
        }
        return set;
    }

    /**
     * Skips Cjenoteka - searches all store sources (Tommy -> Lidl -> Konzum -> Spar -> dm -> OFF)
     * for products currently missing an image.
     */
    public void fetchFromStores() {
        // Clean up bad images first
        int cleaned = jdbc.update(
                "UPDATE products SET image_url = NULL " +
                "WHERE image_url LIKE '%category_thumb%' OR image_url LIKE '%shop_thumb%'");
        if (cleaned > 0) {
            log.info("Cleaned {} bad images from DB", cleaned);
        }

        log.info("Store image fetch: starting pipeline");

        // Tommy (EAN filter, then name)
        runStorePhase("Tommy", p -> {
            String img = searchTommyByBarcode(p[1]);
            if (img == null) img = searchTommyByName(p[2]);
            return img;
        });

        // Lidl (name search)
        runStorePhase("Lidl", p -> searchLidlByName(p[2]));

        // Konzum (name search)
        runStorePhase("Konzum", p -> searchKonzumByName(p[2]));

        // Spar (name search)
        runStorePhase("Spar", p -> searchSparByName(p[2]));

        // dm (name search)
        runStorePhase("dm", p -> searchDmByName(p[2]));

        // Open Food Facts (barcode)
        runStorePhase("OFF", p -> fetchFromOpenFoodFacts(p[1]));

        logFinalStats();
    }

    /**
     * Reloads products that still have no image and runs the given fetcher across them
     * in parallel. Updates {@code image_url} (but not {@code image_searched}) so the same
     * product is retried across every phase in the store pipeline.
     */
    private void runStorePhase(String phase, java.util.function.Function<String[], String> fetcher) {
        List<String[]> products = new ArrayList<>();
        jdbc.query("SELECT id, barcode, name FROM products WHERE image_url IS NULL AND barcode IS NOT NULL AND barcode != ''",
                (java.sql.ResultSet rs) -> {
                    products.add(new String[]{
                            String.valueOf(rs.getLong("id")),
                            rs.getString("barcode"),
                            rs.getString("name")
                    });
                });
        if (products.isEmpty()) {
            log.info("{} phase: nothing to do (no products missing images)", phase);
            return;
        }

        log.info("{} ({} products)", phase, products.size());
        AtomicInteger found = new AtomicInteger(0);
        runParallel(phase, products, p -> {
            String img = fetcher.apply(p);
            if (img != null) {
                jdbc.update("UPDATE products SET image_url = ? WHERE id = ?", img, Long.parseLong(p[0]));
                found.incrementAndGet();
                return true;
            }
            return false;
        });
        log.info("{} done: {} found", phase, found.get());
    }
    // Data loading helpers
    private List<String[]> loadUnsearchedProducts() {
        List<String[]> list = new ArrayList<>();
        jdbc.query("SELECT id, barcode, name FROM products WHERE image_url IS NULL AND image_searched = false AND barcode IS NOT NULL AND barcode != ''",
                rs -> {
                    list.add(new String[]{
                            String.valueOf(rs.getLong("id")),
                            rs.getString("barcode"),
                            rs.getString("name")
                    });
                });
        return list;
    }
    private void logFinalStats() {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE image_url IS NOT NULL", Long.class);
        long missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE image_url IS NULL", Long.class);
        log.info("DONE: {} products have images, {} still without", total, missing);
    }
    // Parallel execution engine
    private void runParallel(String phase, List<String[]> items, ProductProcessor processor) {
        // Update live progress state
        progressPhase = phase;
        progressTotal.set(items.size());
        progressDone.set(0);
        progressFound.set(0);
        progressStartTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
        int total = items.size();

        int chunkSize = (total + PARALLEL_THREADS - 1) / PARALLEL_THREADS;
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < PARALLEL_THREADS; t++) {
            int start = t * chunkSize;
            int end = Math.min(start + chunkSize, total);
            if (start >= total) break;

            List<String[]> chunk = items.subList(start, end);

            futures.add(executor.submit(() -> {
                for (String[] product : chunk) {
                    try {
                        boolean success = processor.process(product);
                        if (success) progressFound.incrementAndGet();
                    } catch (Exception e) { /* skip */ }

                    int done = progressDone.incrementAndGet();
                    if (done % 200 == 0) {
                        int f = progressFound.get();
                        log.info("{}: {}/{} ({} %) - {} found ({} % hit rate)",
                                phase, done, total, (done * 100) / total,
                                f, done > 0 ? (f * 100) / done : 0);
                    }

                    try {
                        Thread.sleep(REQUEST_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) {
                log.warn("{}: thread error: {}", phase, e.getMessage());
            }
        }
        executor.shutdown();

        int f = progressFound.get();
        log.info("{}: COMPLETE - {}/{} found ({} % hit rate)",
                phase, f, total, total > 0 ? (f * 100) / total : 0);
        progressPhase = "idle";
    }

    @FunctionalInterface
    private interface ProductProcessor {
        boolean process(String[] product); // [id, barcode, name]
    }
}

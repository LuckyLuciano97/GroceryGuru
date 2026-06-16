package org.example.groceryguru.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ingests prices, products, and stores from the public cijene-api daily archive.
 * <p>
 * A single ZIP file at {@code https://api.cijene.dev/v0/archive/YYYY-MM-DD.zip}
 * contains folders for ~20 Croatian retail chains (Konzum, Spar, Studenac, Plodine,
 * Lidl, Tommy, Kaufland, Eurospin, dm, KTC, Metro, Trgocentar, Žabac, Vrutak, Ribola,
 * NTL, Roto, Boso, Brodokomerc, Lorenco, etc.) with {@code stores.csv}, {@code products.csv}
 * and {@code prices.csv} inside each folder.
 * <p>
 * This service downloads the archive, parses each chain independently, and re-uses
 * {@link PriceIngestionService#persistPrices(List, String)} to upsert products / stores / prices.
 */
@Service
public class CijeneApiIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CijeneApiIngestionService.class);

    private static final String ARCHIVE_URL_TEMPLATE = "https://api.cijene.dev/v0/archive/%s.zip";

    /** Mapping from folder name (as used in the archive) to human-friendly chain name. */
    private static final Map<String, String> CHAIN_NAME_MAP = Map.ofEntries(
            Map.entry("konzum", "Konzum"),
            Map.entry("spar", "Spar"),
            Map.entry("studenac", "Studenac"),
            Map.entry("plodine", "Plodine"),
            Map.entry("lidl", "Lidl"),
            Map.entry("tommy", "Tommy"),
            Map.entry("kaufland", "Kaufland"),
            Map.entry("eurospin", "Eurospin"),
            Map.entry("dm", "dm"),
            Map.entry("ktc", "KTC"),
            Map.entry("metro", "Metro"),
            Map.entry("trgocentar", "Trgocentar"),
            Map.entry("zabac", "Žabac"),
            Map.entry("vrutak", "Vrutak"),
            Map.entry("ribola", "Ribola"),
            Map.entry("ntl", "NTL"),
            Map.entry("roto", "Roto"),
            Map.entry("boso", "Boso"),
            Map.entry("brodokomerc", "Brodokomerc"),
            Map.entry("lorenco", "Lorenco"),
            Map.entry("trgovina-krk", "Trgovina Krk"),
            Map.entry("branka", "Branka"),
            Map.entry("gavranovic", "Gavranović"),
            Map.entry("djelo_vodice", "Djelo Vodice"),
            Map.entry("jadranka_trgovina", "Jadranka Trgovina")
    );

    private final PriceIngestionService priceIngestionService;
    private final HttpClient httpClient;

    @Value("${ingestion.cijene.enabled:false}")
    private boolean enabled;

    public CijeneApiIngestionService(PriceIngestionService priceIngestionService) {
        this.priceIngestionService = priceIngestionService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Runs daily at 07:00 Europe/Zagreb - one hour before the scheduled per-chain ingestion
     * so fresh data is available via both paths.
     */
    @Scheduled(cron = "${ingestion.cijene.cron:0 0 7 * * *}")
    public void scheduledIngestion() {
        if (!enabled) {
            log.info("Cijene API ingestion is disabled");
            return;
        }
        try {
            IngestionResult result = ingestLatest();
            log.info("Scheduled Cijene API ingestion finished: {}", result);
        } catch (Exception e) {
            log.error("Scheduled Cijene API ingestion failed", e);
        }
    }

    /**
     * Downloads and ingests the latest available archive. Cijene publishes once per
     * day but not exactly at 00:00 Europe/Zagreb - typically a few hours into the
     * morning. This method tries today first and walks backwards up to 7 days until
     * an archive is found, so a midnight run doesn't fail with 404.
     */
    public IngestionResult ingestLatest() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Zagreb"));
        Exception lastError = null;
        for (int daysBack = 0; daysBack <= 7; daysBack++) {
            LocalDate date = today.minusDays(daysBack);
            String url = String.format(ARCHIVE_URL_TEMPLATE, date);
            if (!archiveExists(url)) {
                log.info("No archive yet for {} - trying previous day", date);
                continue;
            }
            log.info("Found latest available archive: {}", date);
            return ingestForDate(date);
        }
        throw new IOException(
                "No cijene-api archive found in the last 7 days starting from " + today,
                lastError);
    }

    /**
     * Cheap HEAD-style probe for an archive URL. Returns {@code true} if the server
     * responds 200, {@code false} for 404 or any other non-200.
     */
    private boolean archiveExists(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "GroceryGuru/1.0 (+https://groceryguru.app)")
                    .timeout(Duration.ofSeconds(15))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("HEAD probe failed for {}: {}", url, e.getMessage());
            return false;
        }
    }

    /** Downloads and ingests the archive for the given date. */
    public IngestionResult ingestForDate(LocalDate date) throws Exception {
        String url = String.format(ARCHIVE_URL_TEMPLATE, date);
        log.info("Downloading cijene-api archive: {}", url);

        Path tempFile = Files.createTempFile("cijene-" + date + "-", ".zip");
        try {
            downloadTo(url, tempFile);
            log.info("Downloaded {} bytes to {}", Files.size(tempFile), tempFile);
            return ingestArchive(tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp archive {}: {}", tempFile, e.getMessage());
            }
        }
    }

    /**
     * Ingests the given local ZIP file (useful for testing or reprocessing a downloaded archive).
     * Iterates through each chain folder and calls {@link PriceIngestionService#persistPrices(List, String)}.
     */
    public IngestionResult ingestArchive(Path zipPath) throws Exception {
        int totalProcessed = 0, totalCreated = 0, totalUpdated = 0, totalErrors = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // Group entries by chain folder
            Map<String, ChainEntries> entriesByChain = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash <= 0) continue; // skip top-level files like archive-info.txt

                String chainKey = name.substring(0, slash);
                String fileName = name.substring(slash + 1);

                ChainEntries ce = entriesByChain.computeIfAbsent(chainKey, k -> new ChainEntries());
                switch (fileName) {
                    case "stores.csv" -> ce.stores = entry;
                    case "products.csv" -> ce.products = entry;
                    case "prices.csv" -> ce.prices = entry;
                    default -> { /* ignore */ }
                }
            }

            log.info("Archive contains {} chain folders", entriesByChain.size());

            // Process each chain
            for (Map.Entry<String, ChainEntries> e : entriesByChain.entrySet()) {
                String folder = e.getKey();
                ChainEntries ce = e.getValue();

                if (ce.stores == null || ce.products == null || ce.prices == null) {
                    log.warn("Chain {} is missing one of stores/products/prices - skipping", folder);
                    continue;
                }

                String chainDisplayName = CHAIN_NAME_MAP.getOrDefault(folder, capitalize(folder));
                try {
                    log.info("Processing chain: {} ({})", chainDisplayName, folder);
                    List<ParsedPrice> parsed = parseChain(zip, folder, ce);
                    if (parsed.isEmpty()) {
                        log.info("Chain {} produced 0 parsed prices - skipping persist", chainDisplayName);
                        continue;
                    }
                    log.info("Chain {}: parsed {} prices, persisting...", chainDisplayName, parsed.size());
                    IngestionResult result = priceIngestionService.persistPrices(parsed, chainDisplayName);
                    totalProcessed += result.processed();
                    totalCreated += result.created();
                    totalUpdated += result.updated();
                    totalErrors += result.errors();
                    log.info("Chain {} done: processed={}, created={}, updated={}, errors={}",
                            chainDisplayName, result.processed(), result.created(),
                            result.updated(), result.errors());
                } catch (Exception ex) {
                    log.error("Failed to ingest chain {}: {}", chainDisplayName, ex.getMessage(), ex);
                    totalErrors++;
                }
            }
        }

        log.info("Cijene API ingestion finished: processed={}, created={}, updated={}, errors={}",
                totalProcessed, totalCreated, totalUpdated, totalErrors);
        return new IngestionResult(totalProcessed, totalCreated, totalUpdated, totalErrors);
    }
    // Per-chain parsing: join stores + products + prices by id -> ParsedPrice
    private List<ParsedPrice> parseChain(ZipFile zip, String folderName, ChainEntries ce) throws IOException {
        Map<String, StoreRow> storesById = parseStores(zip, ce.stores);
        Map<String, ProductRow> productsById = parseProducts(zip, ce.products);

        log.info("Chain {}: {} stores, {} products", folderName, storesById.size(), productsById.size());

        List<ParsedPrice> out = new ArrayList<>();
        try (BufferedReader reader = reader(zip, ce.prices)) {
            String header = reader.readLine(); // skip header
            if (header == null) return out;

            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = parseCsvLine(line);
                if (fields.length < 3) { skipped++; continue; }

                try {
                    String storeId = trim(fields[0]);
                    String productId = trim(fields[1]);
                    BigDecimal price = parseDecimal(fields[2]);
                    BigDecimal specialPrice = fields.length > 6 ? parseDecimal(fields[6]) : null;

                    if (price == null) { skipped++; continue; }

                    StoreRow store = storesById.get(storeId);
                    ProductRow product = productsById.get(productId);
                    if (store == null || product == null) { skipped++; continue; }

                    // Skip stores with no real location (e.g., dm "all,store,,,")
                    if (isBlank(store.city) && isBlank(store.address)) { skipped++; continue; }

                    String prefixedStoreCode = folderName.toUpperCase(Locale.ROOT) + "_" + storeId;
                    String storeDisplayName = defaultIfBlank(
                            CHAIN_NAME_MAP.getOrDefault(folderName, capitalize(folderName))
                                    + (isBlank(store.city) ? "" : " " + store.city),
                            "Store " + storeId);

                    BigDecimal effective = specialPrice != null ? specialPrice : price;

                    out.add(new ParsedPrice(
                            product.name,
                            productId,
                            product.barcode,
                            product.brand,
                            product.quantity,
                            product.unit,
                            product.category,
                            effective,
                            specialPrice,
                            prefixedStoreCode,
                            storeDisplayName,
                            store.address,
                            store.city,
                            parseIntSafe(store.zipcode)
                    ));
                } catch (Exception ex) {
                    skipped++;
                }
            }
            if (skipped > 0) {
                log.info("Chain {}: skipped {} malformed / unresolvable price rows", folderName, skipped);
            }
        }

        return out;
    }

    private Map<String, StoreRow> parseStores(ZipFile zip, ZipEntry entry) throws IOException {
        Map<String, StoreRow> map = new HashMap<>();
        try (BufferedReader reader = reader(zip, entry)) {
            String header = reader.readLine();
            if (header == null) return map;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsvLine(line);
                if (f.length < 5) continue;
                String storeId = trim(f[0]);
                if (storeId.isEmpty()) continue;
                map.put(storeId, new StoreRow(
                        storeId,
                        trim(f[1]),
                        trim(f[2]),
                        trim(f[3]),
                        trim(f[4])
                ));
            }
        }
        return map;
    }

    private Map<String, ProductRow> parseProducts(ZipFile zip, ZipEntry entry) throws IOException {
        Map<String, ProductRow> map = new HashMap<>();
        try (BufferedReader reader = reader(zip, entry)) {
            String header = reader.readLine();
            if (header == null) return map;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsvLine(line);
                if (f.length < 7) continue;
                String productId = trim(f[0]);
                if (productId.isEmpty()) continue;
                map.put(productId, new ProductRow(
                        productId,
                        trim(f[1]),
                        trim(f[2]),
                        trim(f[3]),
                        trim(f[4]),
                        trim(f[5]),
                        trim(f[6])
                ));
            }
        }
        return map;
    }
    // Utilities
    /**
     * Reads a ZIP entry into a BufferedReader, auto-detecting between UTF-8
     * and Windows-1250. The cijene-api archive mixes encodings: most chains
     * publish UTF-8 but a few (Spar, Plodine, NTL, some Studenac batches)
     * use Windows-1250. Forcing UTF-8 silently corrupted Croatian characters
     * (ć/č/š/ž/đ -> U+FFFD), which is what produced names like {@code BU�INE}
     * and {@code MATU�KO} in the products table.
     * <p>
     * We delegate to {@link EncodingUtil#detectAndDecode(byte[])} which is the
     * same logic the other store-specific strategies use.
     */
    private BufferedReader reader(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] bytes = is.readAllBytes();
            String decoded = EncodingUtil.detectAndDecode(bytes);
            return new BufferedReader(new java.io.StringReader(decoded));
        }
    }

    private void downloadTo(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "GroceryGuru/1.0 (+https://groceryguru.app)")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // Handle doubled quote inside quoted field
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replace(",", ".");
        if (cleaned.isEmpty()) return null;
        if (cleaned.startsWith(".")) cleaned = "0" + cleaned;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntSafe(String s) {
        if (s == null) return 0;
        String cleaned = s.trim().replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) return 0;
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String trim(String s) { return s == null ? "" : s.trim(); }
    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private String defaultIfBlank(String s, String fallback) { return isBlank(s) ? fallback : s; }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ').replace('-', ' ');
    }
    // Local data holders
    private static class ChainEntries {
        ZipEntry stores;
        ZipEntry products;
        ZipEntry prices;
    }

    private record StoreRow(String storeId, String type, String address, String city, String zipcode) {}

    private record ProductRow(
            String productId,
            String barcode,
            String name,
            String brand,
            String category,
            String unit,
            String quantity
    ) {}
}

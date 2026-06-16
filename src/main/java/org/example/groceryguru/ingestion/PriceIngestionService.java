package org.example.groceryguru.ingestion;

import org.example.groceryguru.model.StoreChain;
import org.example.groceryguru.repository.StoreChainRepo;
import org.example.groceryguru.service.GeocodingService;
import org.example.groceryguru.service.ProductNameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class PriceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionService.class);
    private static final int BATCH_SIZE = 1000;

    private final List<StoreIngestionStrategy> strategies;
    private final StoreChainRepo storeChainRepo;
    private final JdbcTemplate jdbc;
    private final GeocodingService geocodingService;
    private final ProductNameNormalizer nameNormalizer;

    public PriceIngestionService(List<StoreIngestionStrategy> strategies,
                                  StoreChainRepo storeChainRepo,
                                  JdbcTemplate jdbc,
                                  GeocodingService geocodingService,
                                  ProductNameNormalizer nameNormalizer) {
        this.strategies = strategies;
        this.storeChainRepo = storeChainRepo;
        this.jdbc = jdbc;
        this.geocodingService = geocodingService;
        this.nameNormalizer = nameNormalizer;
    }

    @Scheduled(cron = "${ingestion.cron:0 0 8 * * *}")
    public void scheduledIngestion() {
        log.info("Starting scheduled price ingestion");
        ingestAll();
    }

    public IngestionResult ingestAll() {
        int totalProcessed = 0, totalCreated = 0, totalUpdated = 0, totalErrors = 0;

        for (StoreIngestionStrategy strategy : strategies) {
            try {
                log.info("Running ingestion for: {}", strategy.getChainName());
                IngestionResult result = ingestFromStrategy(strategy);
                totalProcessed += result.processed();
                totalCreated += result.created();
                totalUpdated += result.updated();
                totalErrors += result.errors();
                log.info("Finished {}: processed={}, created={}, updated={}, errors={}",
                        strategy.getChainName(), result.processed(), result.created(),
                        result.updated(), result.errors());
            } catch (Exception e) {
                log.error("Failed ingestion for {}: {}", strategy.getChainName(), e.getMessage(), e);
                totalErrors++;
            }
        }

        return new IngestionResult(totalProcessed, totalCreated, totalUpdated, totalErrors);
    }

    public IngestionResult ingestFromStrategy(StoreIngestionStrategy strategy) throws Exception {
        List<ParsedPrice> parsedPrices = strategy.fetchAndParse();
        return persistPrices(parsedPrices, strategy.getChainName());
    }

    @Transactional
    public IngestionResult persistPrices(List<ParsedPrice> parsedPrices, String chainName) {
        int created = 0, updated = 0, errors = 0;
        log.info("Persisting {} parsed prices for {}", parsedPrices.size(), chainName);

        // Step 1: Ensure chain exists
        StoreChain chain = storeChainRepo.findByName(chainName)
                .orElseGet(() -> {
                    StoreChain c = new StoreChain();
                    c.setName(chainName);
                    c.setCountryCode("HR");
                    return storeChainRepo.save(c);
                });
        Long chainId = chain.getId();

        // Step 2: Load existing products by barcode (1 query)
        log.info("Loading existing products...");
        Map<String, Long> productIdByBarcode = new HashMap<>();
        jdbc.query("SELECT id, barcode FROM products WHERE barcode IS NOT NULL", rs -> {
            productIdByBarcode.put(rs.getString("barcode"), rs.getLong("id"));
        });
        log.info("Loaded {} existing products", productIdByBarcode.size());

        // Step 3: Load existing stores by code (1 query)
        log.info("Loading existing stores...");
        Map<String, Long> storeIdByCode = new HashMap<>();
        jdbc.query("SELECT id, store_code FROM stores WHERE store_code IS NOT NULL", rs -> {
            storeIdByCode.put(rs.getString("store_code"), rs.getLong("id"));
        });
        log.info("Loaded {} existing stores", storeIdByCode.size());

        // Step 4: Collect new products (deduplicate by barcode)
        Map<String, ParsedPrice> newProductsByBarcode = new LinkedHashMap<>();
        for (ParsedPrice pp : parsedPrices) {
            if (pp.barcode() != null && !pp.barcode().isBlank()
                    && !productIdByBarcode.containsKey(pp.barcode())
                    && !newProductsByBarcode.containsKey(pp.barcode())) {
                newProductsByBarcode.put(pp.barcode(), pp);
            }
        }

        // Step 5: Batch insert new products via JDBC
        if (!newProductsByBarcode.isEmpty()) {
            log.info("Inserting {} new products via JDBC batch...", newProductsByBarcode.size());
            List<Map.Entry<String, ParsedPrice>> entries = new ArrayList<>(newProductsByBarcode.entrySet());

            for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, entries.size());
                List<Map.Entry<String, ParsedPrice>> batch = entries.subList(i, end);

                jdbc.execute("SELECT 1", (PreparedStatement ps) -> {
                    return null;
                });

                String sql = "INSERT INTO products (name, display_name, barcode, brand, category, net_quantity, unit) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (barcode) DO UPDATE " +
                        "SET name = EXCLUDED.name, display_name = EXCLUDED.display_name, " +
                        "brand = EXCLUDED.brand, category = EXCLUDED.category, " +
                        "net_quantity = EXCLUDED.net_quantity, unit = EXCLUDED.unit";

                jdbc.batchUpdate(sql, batch, BATCH_SIZE, (ps, entry) -> {
                    ParsedPrice pp = entry.getValue();
                    String rawName  = truncate(pp.productName(), 150);
                    String brandStr = truncate(pp.brand(), 100);
                    String display  = truncate(nameNormalizer.normalize(rawName, brandStr), 200);
                    ps.setString(1, rawName);
                    ps.setString(2, display);
                    ps.setString(3, pp.barcode());
                    ps.setString(4, brandStr);
                    ps.setString(5, truncate(pp.category(), 100));
                    ps.setString(6, truncate(pp.netQuantity(), 50));
                    ps.setString(7, truncate(pp.unit(), 20));
                });
            }

            // Reload product map
            productIdByBarcode.clear();
            jdbc.query("SELECT id, barcode FROM products WHERE barcode IS NOT NULL", rs -> {
                productIdByBarcode.put(rs.getString("barcode"), rs.getLong("id"));
            });
            log.info("Products now: {}", productIdByBarcode.size());
        }

        // Step 6: Collect and batch insert new stores
        Map<String, ParsedPrice> newStoresByCode = new LinkedHashMap<>();
        for (ParsedPrice pp : parsedPrices) {
            if (!storeIdByCode.containsKey(pp.storeCode())
                    && !newStoresByCode.containsKey(pp.storeCode())) {
                newStoresByCode.put(pp.storeCode(), pp);
            }
        }

        if (!newStoresByCode.isEmpty()) {
            log.info("Inserting {} new stores via JDBC batch...", newStoresByCode.size());
            String sql = "INSERT INTO stores (name, store_code, street, city, postal_code, chain_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (store_code) DO NOTHING";

            List<Map.Entry<String, ParsedPrice>> storeEntries = new ArrayList<>(newStoresByCode.entrySet());
            jdbc.batchUpdate(sql, storeEntries, BATCH_SIZE, (ps, entry) -> {
                ParsedPrice pp = entry.getValue();
                ps.setString(1, truncate(pp.storeName(), 100));
                ps.setString(2, pp.storeCode());
                ps.setString(3, truncate(pp.storeStreet(), 255));
                ps.setString(4, pp.storeCity());
                ps.setInt(5, pp.postalCode());
                ps.setLong(6, chainId);
            });

            // Reload store map
            storeIdByCode.clear();
            jdbc.query("SELECT id, store_code FROM stores WHERE store_code IS NOT NULL", rs -> {
                storeIdByCode.put(rs.getString("store_code"), rs.getLong("id"));
            });
            log.info("Stores now: {}", storeIdByCode.size());

            // Geocode new stores
            geocodeNewStores(newStoresByCode);
        }

        // Step 7: Load existing current price keys into memory
        log.info("Loading existing price index...");
        Set<String> existingPriceKeys = new HashSet<>();
        Map<String, BigDecimal> existingPriceValues = new HashMap<>();
        jdbc.query("SELECT product_id, store_id, price FROM prices WHERE is_current = true", rs -> {
            String key = rs.getLong("product_id") + "_" + rs.getLong("store_id");
            existingPriceKeys.add(key);
            existingPriceValues.put(key, rs.getBigDecimal("price"));
        });
        log.info("Loaded {} existing prices", existingPriceKeys.size());

        // Ensure new columns exist (safe to run multiple times)
        try {
            jdbc.execute("ALTER TABLE prices ADD COLUMN IF NOT EXISTS regular_price NUMERIC");
            jdbc.execute("ALTER TABLE prices ADD COLUMN IF NOT EXISTS on_sale BOOLEAN NOT NULL DEFAULT false");
        } catch (Exception e) {
            log.debug("Columns already exist or cannot add: {}", e.getMessage());
        }

        // Step 8: Separate into inserts and updates
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        List<Object[]> priceInserts = new ArrayList<>();
        List<Object[]> priceUpdates = new ArrayList<>();

        for (ParsedPrice pp : parsedPrices) {
            if (pp.barcode() == null || pp.barcode().isBlank()) continue;

            // Skip zero/negative prices - bad data from source
            if (pp.retailPrice() == null || pp.retailPrice().compareTo(BigDecimal.ZERO) <= 0) {
                errors++;
                continue;
            }

            Long productId = productIdByBarcode.get(pp.barcode());
            Long storeId = storeIdByCode.get(pp.storeCode());

            if (productId == null || storeId == null) {
                errors++;
                continue;
            }

            String key = productId + "_" + storeId;
            boolean isOnSale = pp.salePrice() != null;
            BigDecimal regularPrice = isOnSale ? pp.retailPrice() : null;
            // If on sale, retailPrice in ParsedPrice is already the effective (sale) price

            if (existingPriceKeys.contains(key)) {
                BigDecimal oldPrice = existingPriceValues.get(key);
                if (oldPrice == null || oldPrice.compareTo(pp.retailPrice()) != 0) {
                    priceUpdates.add(new Object[]{pp.retailPrice(), ts, regularPrice, isOnSale, productId, storeId});
                    updated++;
                }
            } else {
                priceInserts.add(new Object[]{pp.retailPrice(), productId, storeId, ts, true, regularPrice, isOnSale});
                existingPriceKeys.add(key);
                created++;
            }
        }

        // Step 9: Batch insert new prices
        if (!priceInserts.isEmpty()) {
            log.info("Batch inserting {} new prices...", priceInserts.size());
            String sql = "INSERT INTO prices (price, product_id, store_id, timestamp, is_current, regular_price, on_sale) VALUES (?, ?, ?, ?, ?, ?, ?)";
            for (int i = 0; i < priceInserts.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, priceInserts.size());
                List<Object[]> batch = priceInserts.subList(i, end);
                jdbc.batchUpdate(sql, batch);
            }
        }

        // Step 10: Batch update changed prices
        if (!priceUpdates.isEmpty()) {
            log.info("Batch updating {} changed prices...", priceUpdates.size());
            String sql = "UPDATE prices SET price = ?, timestamp = ?, regular_price = ?, on_sale = ? WHERE product_id = ? AND store_id = ? AND is_current = true";
            for (int i = 0; i < priceUpdates.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, priceUpdates.size());
                List<Object[]> batch = priceUpdates.subList(i, end);
                jdbc.batchUpdate(sql, batch);
            }
        }

        int processed = created + updated;
        log.info("Ingestion complete: processed={}, created={}, updated={}, errors={}", processed, created, updated, errors);
        return new IngestionResult(processed, created, updated, errors);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    /**
     * Enqueues newly inserted stores for geocoding via the shared single-threaded worker
     * in {@link GeocodingService}. The ingestion thread is NOT blocked - requests are
     * processed in the background at the Nominatim-compliant rate of 1 / sec.
     * <p>
     * This approach is safe even when multiple chains are ingested back-to-back: all
     * their stores queue up on the same worker thread, so the 1 req/sec limit is never
     * violated regardless of how many chains are running.
     */
    private void geocodeNewStores(Map<String, ParsedPrice> newStores) {
        log.info("Enqueuing geocoding for {} new stores (background, 1/sec rate limit)",
                newStores.size());
        for (ParsedPrice pp : newStores.values()) {
            String storeCode = pp.storeCode();
            geocodingService.geocodeAsync(
                    pp.storeStreet(), pp.storeCity(), pp.postalCode(),
                    (result, error) -> {
                        if (error != null) {
                            log.debug("Geocoding error for {}: {}", storeCode, error.getMessage());
                            return;
                        }
                        if (result != null) {
                            try {
                                jdbc.update(
                                        "UPDATE stores SET latitude = ?, longitude = ? WHERE store_code = ?",
                                        result.latitude(), result.longitude(), storeCode);
                            } catch (Exception e) {
                                log.debug("Failed to save coordinates for {}: {}", storeCode, e.getMessage());
                            }
                        }
                    }
            );
        }
    }
}

package org.example.groceryguru.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Matches our products to cached Cjenoteka products entirely in SQL.
 *
 * <p>Two passes:
 * <ol>
 *   <li><b>Exact barcode match</b> - JOIN on barcodes array, zero false positives.</li>
 *   <li><b>pg_trgm similarity match</b> - character-level trigram similarity between
 *       product names; uses a GIN index for speed. Configurable threshold.</li>
 * </ol>
 *
 * <p>All matching is offline (no HTTP) and produces auditable SQL results.
 * Run {@code /api/products/build-cjenoteka-cache} first to populate the cache.
 */
@Service
public class LocalImageMatcherService {

    private static final Logger log = LoggerFactory.getLogger(LocalImageMatcherService.class);

    private final JdbcTemplate jdbc;

    @Value("${image.match.similarity-threshold:0.55}")
    private double similarityThreshold;

    public LocalImageMatcherService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs both matching passes and returns a summary map with counts.
     * Typically completes in seconds even for 100K+ products.
     */
    public Map<String, Object> matchAll() {
        log.info("Local image matching via pg_trgm (threshold={})", similarityThreshold);

        // Guard: ensure cache exists and has data
        long cacheSize = cacheCount();
        if (cacheSize == 0) {
            log.warn("Cjenoteka cache is empty - run /api/products/build-cjenoteka-cache first");
            return Map.of("error",
                    "Cjenoteka cache is empty. Run GET /api/products/build-cjenoteka-cache first.");
        }
        log.info("Cache has {} products", cacheSize);

        // --- Pass 1: Exact barcode match ---
        // JOIN on our barcode being present in cjenoteka_products.barcodes[].
        // GIN index on barcodes makes this fast. Zero false positives.
        log.info("Pass 1: exact barcode matching...");
        int barcodeMatched = jdbc.update("""
            UPDATE products p
            SET    image_url = (
                       SELECT c.image_url
                       FROM   cjenoteka_products c
                       WHERE  p.barcode = ANY(c.barcodes)
                       LIMIT  1
                   )
            WHERE  p.image_url IS NULL
              AND  p.barcode   IS NOT NULL
              AND  EXISTS (
                       SELECT 1 FROM cjenoteka_products c
                       WHERE  p.barcode = ANY(c.barcodes)
                   )
            """);
        log.info("Pass 1 complete: {} products matched by exact barcode", barcodeMatched);

        // --- Pass 2: pg_trgm similarity match ---
        // LATERAL join: for each product still missing an image, find the
        // Cjenoteka product whose name has the highest trigram similarity.
        // The GIN index on lower(name) makes this fast (no full cross-join).
        // SET similarity_threshold BEFORE the query so the % operator uses it.
        log.info("Pass 2: pg_trgm name similarity matching...");
        jdbc.execute("SET pg_trgm.similarity_threshold = " + similarityThreshold);

        int similarityMatched = jdbc.update("""
            UPDATE products p
            SET    image_url = match.image_url
            FROM (
                SELECT p.id, top.image_url
                FROM   products p
                JOIN LATERAL (
                    SELECT c.image_url,
                           lower(c.name) <-> lower(p.name) AS dist
                    FROM   cjenoteka_products c
                    WHERE  lower(c.name) % lower(p.name)
                    ORDER  BY dist
                    LIMIT  1
                ) top ON true
                WHERE  p.image_url IS NULL
                  AND  p.name IS NOT NULL
            ) match
            WHERE  p.id = match.id
            """);
        log.info("Pass 2 complete: {} products matched by name similarity", similarityMatched);

        // --- Final stats ---
        long withImage    = count("SELECT COUNT(*) FROM products WHERE image_url IS NOT NULL");
        long withoutImage = count("SELECT COUNT(*) FROM products WHERE image_url IS NULL");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("barcodeMatched",    barcodeMatched);
        result.put("similarityMatched", similarityMatched);
        result.put("totalMatched",      barcodeMatched + similarityMatched);
        result.put("dbWithImage",       withImage);
        result.put("dbWithoutImage",    withoutImage);
        log.info("Matching done: +{} via barcode, +{} via similarity, {} total with image",
                barcodeMatched, similarityMatched, withImage);
        return result;
    }

    /**
     * Dry-run: returns a sample of the top similarity matches WITHOUT applying them.
     * Useful for tuning the threshold before committing.
     */
    public Map<String, Object> previewMatches(int limit) {
        jdbc.execute("SET pg_trgm.similarity_threshold = " + similarityThreshold);
        var rows = jdbc.queryForList("""
            SELECT p.id, p.name AS product_name, c.name AS cache_name,
                   c.image_url,
                   round(similarity(lower(p.name), lower(c.name))::numeric, 3) AS sim
            FROM   products p
            JOIN LATERAL (
                SELECT c.name, c.image_url,
                       lower(c.name) <-> lower(p.name) AS dist
                FROM   cjenoteka_products c
                WHERE  lower(c.name) % lower(p.name)
                ORDER  BY dist
                LIMIT  1
            ) c ON true
            WHERE  p.image_url IS NULL
              AND  p.name IS NOT NULL
            ORDER  BY sim DESC
            LIMIT  ?
            """, limit);
        return Map.of(
                "threshold", similarityThreshold,
                "sampleSize", rows.size(),
                "matches", rows
        );
    }

    // --- Helpers ---
    private long cacheCount() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cjenoteka_products", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}

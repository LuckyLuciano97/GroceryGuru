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
 * Pass 1: exact barcode match against the cache's barcodes[] arrays - a GTIN
 * match identifies the exact same physical product, so it is unconditional.
 *
 * Pass 2: gated name matching. Trigram similarity only nominates candidates;
 * a candidate is accepted ONLY if every gate passes:
 *   - size gate:   the size parsed from our product name equals the cache
 *                  product's structured amount+unit (normalized to g/ml)
 *   - brand gate:  when both sides know a brand, they must agree (or the
 *                  cache brand must appear in our product name)
 *   - margin gate: the best candidate must clearly beat the runner-up,
 *                  otherwise the match is ambiguous and nothing is assigned
 * Products that fail any gate keep no image (category icon fallback) - a
 * missing image is recoverable, a wrong image is not.
 */
@Service
public class LocalImageMatcherService {

    private static final Logger log = LoggerFactory.getLogger(LocalImageMatcherService.class);

    private final JdbcTemplate jdbc;

    @Value("${image.match.similarity-threshold:0.60}")
    private double similarityThreshold;

    /** Winner must beat runner-up by this much, unless both share one image. */
    @Value("${image.match.margin:0.05}")
    private double margin;

    public LocalImageMatcherService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Size parsed from OUR product name, normalized to grams/ml.
    // (net_quantity is too inconsistent in the feed to rely on.)
    private static final String P_SIZE = """
        (SELECT CASE m[2]
                  WHEN 'kg' THEN m[1]::numeric * 1000
                  WHEN 'l'  THEN m[1]::numeric * 1000
                  WHEN 'dl' THEN m[1]::numeric * 100
                  WHEN 'cl' THEN m[1]::numeric * 10
                  WHEN 'g'  THEN m[1]::numeric
                  WHEN 'ml' THEN m[1]::numeric
                END
         FROM regexp_match(replace(lower(p.name), ',', '.'),
                           '(\\d+\\.?\\d*) ?(kg|ml|dl|cl|g|l)\\M') AS m)""";

    // Cache side: structured amount+unit normalized the same way.
    private static final String C_SIZE = """
        c.amount * CASE lower(c.unit)
                     WHEN 'kg' THEN 1000 WHEN 'l' THEN 1000
                     WHEN 'dl' THEN 100  WHEN 'cl' THEN 10
                     WHEN 'g'  THEN 1    WHEN 'ml' THEN 1
                   END""";

    private String gatedCte() {
        return """
            WITH cand AS (
                SELECT p.id AS pid, p.name AS pname, p.brand AS pbrand,
                       %s AS psize,
                       top.image_url, top.cname, top.cbrand, top.csize, top.sim
                FROM   products p
                JOIN LATERAL (
                    SELECT c.image_url, c.name AS cname, c.brand AS cbrand,
                           %s AS csize,
                           similarity(lower(c.name), lower(p.name)) AS sim
                    FROM   cjenoteka_products c
                    WHERE  lower(c.name) %% lower(p.name)
                    ORDER  BY lower(c.name) <-> lower(p.name)
                    LIMIT  2
                ) top ON true
                WHERE  p.image_url IS NULL
                  AND  p.name IS NOT NULL
            ),
            ranked AS (
                SELECT pid,
                       max(pname)  AS pname,
                       max(pbrand) AS pbrand,
                       max(psize)  AS psize,
                       (array_agg(image_url ORDER BY sim DESC))[1] AS img1,
                       (array_agg(image_url ORDER BY sim DESC))[2] AS img2,
                       (array_agg(cname     ORDER BY sim DESC))[1] AS cname1,
                       (array_agg(cbrand    ORDER BY sim DESC))[1] AS cbrand1,
                       (array_agg(csize     ORDER BY sim DESC))[1] AS csize1,
                       (array_agg(sim       ORDER BY sim DESC))[1] AS s1,
                       (array_agg(sim       ORDER BY sim DESC))[2] AS s2
                FROM   cand
                GROUP  BY pid
            ),
            accepted AS (
                SELECT * FROM ranked
                WHERE  s1 >= %f
                  AND  psize  IS NOT NULL
                  AND  csize1 IS NOT NULL
                  AND  abs(psize - csize1) < 0.01
                  AND  ( pbrand IS NULL OR pbrand = '' OR cbrand1 IS NULL
                         OR lower(cbrand1) = lower(pbrand)
                         OR position(lower(cbrand1) IN lower(pname)) > 0 )
                  AND  ( s2 IS NULL OR img2 = img1 OR s1 - s2 >= %f )
            )
            """.formatted(P_SIZE, C_SIZE, similarityThreshold, margin);
    }

    /** Runs both passes and returns a summary with per-gate rejection counts. */
    public Map<String, Object> matchAll() {
        long cacheSize = cacheCount();
        if (cacheSize == 0) {
            return Map.of("error",
                    "Cjenoteka cache is empty. Run GET /api/products/build-cjenoteka-cache first.");
        }
        log.info("Gated image matching (threshold={}, margin={}, cache={})",
                similarityThreshold, margin, cacheSize);

        // --- Pass 1: exact barcode (GTIN) - unconditional ---
        int barcodeMatched = jdbc.update("""
            UPDATE products p
            SET    image_url = (
                       SELECT c.image_url FROM cjenoteka_products c
                       WHERE  p.barcode = ANY(c.barcodes) LIMIT 1)
            WHERE  p.image_url IS NULL
              AND  p.barcode IS NOT NULL
              AND  EXISTS (SELECT 1 FROM cjenoteka_products c
                           WHERE p.barcode = ANY(c.barcodes))
            """);
        log.info("Pass 1: {} matched by exact barcode", barcodeMatched);

        // --- Pass 2: gated name matching ---
        jdbc.execute("SET pg_trgm.similarity_threshold = " + similarityThreshold);
        int gatedMatched = jdbc.update(gatedCte() + """
            UPDATE products p
            SET    image_url = a.img1
            FROM   accepted a
            WHERE  p.id = a.pid
            """);
        log.info("Pass 2: {} matched by gated name match", gatedMatched);

        long withImage    = count("SELECT COUNT(*) FROM products WHERE image_url IS NOT NULL");
        long withoutImage = count("SELECT COUNT(*) FROM products WHERE image_url IS NULL");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("barcodeMatched", barcodeMatched);
        result.put("gatedNameMatched", gatedMatched);
        result.put("totalMatched", barcodeMatched + gatedMatched);
        result.put("dbWithImage", withImage);
        result.put("dbWithoutImage", withoutImage);
        return result;
    }

    /**
     * Dry-run of Pass 2: shows what WOULD be accepted, plus how many candidates
     * each gate rejected - so the gates can be audited before applying.
     */
    public Map<String, Object> previewMatches(int limit) {
        jdbc.execute("SET pg_trgm.similarity_threshold = " + similarityThreshold);

        var gateStats = jdbc.queryForMap(gatedCte().replace("accepted AS (",
            "accepted AS (") + """
            SELECT
              count(*)                                                    AS candidates,
              count(*) FILTER (WHERE s1 < %f)                             AS below_threshold,
              count(*) FILTER (WHERE s1 >= %f AND (psize IS NULL OR csize1 IS NULL
                                                   OR abs(psize-csize1) >= 0.01)) AS rejected_size,
              count(*) FILTER (WHERE s1 >= %f AND psize IS NOT NULL AND csize1 IS NOT NULL
                               AND abs(psize-csize1) < 0.01
                               AND NOT ( pbrand IS NULL OR pbrand = '' OR cbrand1 IS NULL
                                         OR lower(cbrand1) = lower(pbrand)
                                         OR position(lower(cbrand1) IN lower(pname)) > 0 )) AS rejected_brand,
              (SELECT count(*) FROM accepted)                             AS accepted
            FROM ranked
            """.formatted(similarityThreshold, similarityThreshold, similarityThreshold));

        var rows = jdbc.queryForList(gatedCte() + """
            SELECT pid, pname, cname1 AS matched_name, cbrand1 AS matched_brand,
                   psize, csize1, round(s1::numeric,3) AS sim, img1 AS image_url
            FROM   accepted
            ORDER  BY s1 DESC
            LIMIT  ?
            """, limit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", similarityThreshold);
        out.put("margin", margin);
        out.put("gateStats", gateStats);
        out.put("sample", rows);
        return out;
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

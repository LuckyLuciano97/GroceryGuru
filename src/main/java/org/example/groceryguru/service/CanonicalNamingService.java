package org.example.groceryguru.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Unifies names across duplicate product rows. The same physical product can
 * appear as multiple rows because some chains publish barcodes as floats
 * ("123.0") and others as integers ("123"), so the barcode-unique constraint
 * doesn't dedupe them.
 *
 * For each group that collapses to one barcode after stripping a trailing ".0",
 * we pick the name from the most TRUSTED chain carrying any row in the group
 * (cleaner data first) and apply that one display name to every row. Rows are
 * kept; only display_name is unified.
 */
@Service
public class CanonicalNamingService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalNamingService.class);

    /** Chains with cleaner naming, most-trusted first. Others rank lowest. */
    private static final List<String> TRUST = List.of(
            "konzum", "kaufland", "lidl", "spar", "plodine", "tommy", "dm",
            "studenac", "eurospin", "ktc", "ntl", "metro");

    private final JdbcTemplate jdbc;
    private final ProductNameNormalizer normalizer;

    public CanonicalNamingService(JdbcTemplate jdbc, ProductNameNormalizer normalizer) {
        this.jdbc = jdbc;
        this.normalizer = normalizer;
    }

    public Map<String, Object> unify(boolean apply, int previewLimit) {
        // All rows that belong to a duplicate group (same barcode after stripping ".0").
        record Row(long id, String name, String nb) {}
        List<Row> rows = new ArrayList<>();
        jdbc.query(
            "SELECT p.id, p.name, regexp_replace(p.barcode, '\\.0+$', '') AS nb " +
            "FROM products p " +
            "JOIN (SELECT regexp_replace(barcode, '\\.0+$', '') nb FROM products " +
            "      GROUP BY 1 HAVING COUNT(*) > 1) d " +
            "  ON regexp_replace(p.barcode, '\\.0+$', '') = d.nb",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                rows.add(new Row(rs.getLong("id"), rs.getString("name"), rs.getString("nb"))));

        if (rows.isEmpty()) {
            return Map.of("applied", apply, "groups", 0, "rowsUpdated", 0, "preview", List.of());
        }

        // Best (lowest) trust rank per product id, from the chains that price it.
        Map<Long, Integer> bestRank = new HashMap<>();
        StringJoiner ids = new StringJoiner(",");
        for (Row r : rows) ids.add(String.valueOf(r.id()));
        jdbc.query(
            "SELECT pr.product_id, sc.name AS chain FROM prices pr " +
            "JOIN stores s ON s.id = pr.store_id " +
            "JOIN store_chains sc ON sc.id = s.chain_id " +
            "WHERE pr.is_current = true AND pr.product_id IN (" + ids + ") " +
            "GROUP BY pr.product_id, sc.name",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                long pid = rs.getLong("product_id");
                int rank = trustRank(rs.getString("chain"));
                bestRank.merge(pid, rank, Math::min);
            });

        // Group rows by normalized barcode.
        Map<String, List<Row>> groups = new HashMap<>();
        for (Row r : rows) groups.computeIfAbsent(r.nb(), k -> new ArrayList<>()).add(r);

        List<Object[]> updates = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();

        for (List<Row> group : groups.values()) {
            // Canonical = name of the row with the best trust rank; tie-break on the
            // longer name (usually carries more useful detail).
            Row chosen = group.stream()
                .min(Comparator
                    .comparingInt((Row r) -> bestRank.getOrDefault(r.id(), Integer.MAX_VALUE))
                    .thenComparing(r -> -r.name().length()))
                .orElseThrow();
            String canonicalDisplay = normalizer.normalize(chosen.name(), null);

            for (Row r : group) {
                updates.add(new Object[]{canonicalDisplay, r.id()});
            }
            if (preview.size() < previewLimit && group.size() > 1) {
                preview.add(Map.of(
                    "barcode", chosen.nb(),
                    "chosenFrom", chosen.name(),
                    "appliedDisplay", canonicalDisplay));
            }
        }

        if (apply && !updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE products SET display_name = ? WHERE id = ?", updates);
            log.info("Canonical naming unified {} rows across {} groups", updates.size(), groups.size());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", apply);
        out.put("groups", groups.size());
        out.put("rowsUpdated", updates.size());
        out.put("preview", preview);
        return out;
    }

    /** Lower = more trusted. Unknown chains rank after all trusted ones. */
    private int trustRank(String chainName) {
        if (chainName == null) return TRUST.size();
        String n = chainName.toLowerCase();
        for (int i = 0; i < TRUST.size(); i++) {
            if (n.contains(TRUST.get(i))) return i;
        }
        return TRUST.size();
    }
}

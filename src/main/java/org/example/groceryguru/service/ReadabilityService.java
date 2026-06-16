package org.example.groceryguru.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites display names so every descriptive word is a full, readable word:
 * truncations are expanded (curated dictionary, then corpus auto-completion),
 * codes/junk are dropped, and the body is capped to a few words. Learns from
 * the whole product corpus so it needs no exhaustive hand-built list.
 */
@Service
public class ReadabilityService {

    private static final Logger log = LoggerFactory.getLogger(ReadabilityService.class);
    private static final Pattern WORD = Pattern.compile("[a-zčćšžđ]+");

    // A 3-4 char token is a real word if it's this common AND no longer form dominates.
    private static final int REAL_WORD_FREQ = 300;
    // Minimum frequency for a corpus auto-completion to be trusted.
    private static final int MIN_COMPLETION_FREQ = 200;

    private final JdbcTemplate jdbc;
    private final ProductNameNormalizer normalizer;

    public ReadabilityService(JdbcTemplate jdbc, ProductNameNormalizer normalizer) {
        this.jdbc = jdbc;
        this.normalizer = normalizer;
    }

    public Map<String, Object> improve(boolean apply, int maxWords, int previewLimit) {
        // Word-frequency corpus from every product name.
        TreeMap<String, Integer> freq = new TreeMap<>();
        jdbc.query("SELECT name FROM products WHERE name IS NOT NULL",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Matcher m = WORD.matcher(rs.getString("name").toLowerCase());
                while (m.find()) {
                    String w = m.group();
                    if (w.length() >= 2) freq.merge(w, 1, Integer::sum);
                }
            });
        log.info("Readability corpus: {} distinct words", freq.size());

        // Classify each distinct token once (expand / keep / drop).
        Map<String, String> cache = new HashMap<>();
        ProductNameNormalizer.TokenResolver resolver =
                key -> cache.computeIfAbsent(key, k -> classify(k, freq));

        List<Object[]> updates = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        var rows = jdbc.queryForList(
                "SELECT id, name, display_name FROM products WHERE name IS NOT NULL");
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String name = (String) row.get("name");
            String old = (String) row.get("display_name");
            String fixed = normalizer.normalize(name, null, resolver, maxWords);
            if (fixed != null && !fixed.isBlank() && !fixed.equals(old)) {
                updates.add(new Object[]{fixed, id});
                if (preview.size() < previewLimit) {
                    preview.add(Map.of("before", old == null ? name : old, "after", fixed));
                }
            }
        }

        if (apply && !updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE products SET display_name = ? WHERE id = ?", updates);
            log.info("Readability applied to {} products", updates.size());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", apply);
        out.put("maxWords", maxWords);
        out.put("changed", updates.size());
        out.put("preview", preview);
        return out;
    }

    /** Returns a full word to use, "" to drop, or null to keep as-is. */
    private String classify(String key, TreeMap<String, Integer> freq) {
        if (normalizer.hasExpansion(key)) return null;   // curated expansion handles it
        if (key.length() >= 5) return null;              // long enough to be a real word
        if (key.length() <= 2) return "";                // 2-char tokens are codes -> drop

        // 3-4 char token: keep only if it's a common standalone word with no
        // dominant longer form; otherwise expand to the corpus completion or drop.
        int f = freq.getOrDefault(key, 0);
        String best = null;
        int bestF = 0;
        for (var e : freq.tailMap(key, false).entrySet()) {
            if (!e.getKey().startsWith(key)) break;      // past the prefix range
            if (e.getKey().length() > key.length() && e.getValue() > bestF) {
                best = e.getKey();
                bestF = e.getValue();
            }
        }
        if (f >= REAL_WORD_FREQ && bestF < f * 2) return null;       // genuine short word
        if (best != null && bestF >= MIN_COMPLETION_FREQ) return best; // expand truncation
        return "";                                                     // drop
    }
}

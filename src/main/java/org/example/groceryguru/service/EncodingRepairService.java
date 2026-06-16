package org.example.groceryguru.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs names where Croatian diacritics were lost to the U+FFFD replacement
 * character (corruption that arrives baked into the upstream feed).
 *
 * Since U+FFFD doesn't record which letter it replaced, we reconstruct it from
 * our own clean corpus: try each diacritic in the gap and keep the candidate
 * that is a real word elsewhere in the data. If nothing matches, fall back to
 * the ASCII base letter (C/S/Z/D) determined from the diacritic-folded corpus,
 * and finally to 'C' as a last resort.
 */
@Service
public class EncodingRepairService {

    private static final Logger log = LoggerFactory.getLogger(EncodingRepairService.class);
    private static final char FFFD = '�';
    // Treat the replacement char as part of a word so corrupted tokens stay whole.
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\uFFFD]+");

    // Uppercase Croatian diacritics and their ASCII base letters.
    private static final char[] DIACRITICS = {'Č', 'Ć', 'Š', 'Ž', 'Đ'};
    private static final char[] BASES      = {'C', 'S', 'Z', 'D'};

    private final JdbcTemplate jdbc;
    private final ProductNameNormalizer normalizer;

    public EncodingRepairService(JdbcTemplate jdbc, ProductNameNormalizer normalizer) {
        this.jdbc = jdbc;
        this.normalizer = normalizer;
    }

    public Map<String, Object> repair(boolean apply, int previewLimit) {
        String fffd = String.valueOf(FFFD);

        // Build corpus of clean uppercase words: one with diacritics, one folded to ASCII.
        Set<String> corpus = new HashSet<>();
        Set<String> folded = new HashSet<>();
        jdbc.query("SELECT name FROM products WHERE name IS NOT NULL AND position(? in name) = 0",
                rs -> {
                    Matcher m = WORD.matcher(rs.getString("name").toUpperCase());
                    while (m.find()) {
                        String w = m.group();
                        corpus.add(w);
                        folded.add(fold(w));
                    }
                }, fffd);
        log.info("Encoding repair corpus: {} clean words, {} folded", corpus.size(), folded.size());

        List<Object[]> updates = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        int[] counts = {0, 0, 0}; // FFFD repair: exact, base, default
        int mojibakeFixed = 0;

        // Two corruption types: UTF-8-as-CP1250 mojibake (recoverable round-trip)
        // and lost diacritics (U+FFFD, corpus-reconstructed).
        var corrupted = jdbc.queryForList(
                "SELECT id, name FROM products WHERE position(? in name) > 0 " +
                "OR name LIKE '%Ä%' OR name LIKE '%Ĺ%' OR name LIKE '%â%' OR name LIKE '%Å%'", fffd);
        for (var row : corrupted) {
            long id = ((Number) row.get("id")).longValue();
            String raw = (String) row.get("name");

            String fixed = fixMojibake(raw);     // recoverable round-trip first
            if (fixed != null) {
                mojibakeFixed++;
            } else {
                fixed = repairName(raw, corpus, folded, counts); // FFFD reconstruction
            }

            if (!fixed.equals(raw)) {
                String display = normalizer.normalize(fixed, null);
                updates.add(new Object[]{fixed, display, id});
                if (preview.size() < previewLimit) {
                    preview.add(Map.of("before", raw, "after", fixed));
                }
            }
        }

        if (apply && !updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE products SET name = ?, display_name = ? WHERE id = ?", updates);
            log.info("Encoding repair applied to {} products", updates.size());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", apply);
        out.put("corruptedFound", corrupted.size());
        out.put("repaired", updates.size());
        out.put("viaMojibakeRoundtrip", mojibakeFixed);
        out.put("viaExactWord", counts[0]);
        out.put("viaBaseLetter", counts[1]);
        out.put("viaDefault", counts[2]);
        out.put("preview", preview);
        return out;
    }

    private String repairName(String name, Set<String> corpus, Set<String> folded, int[] counts) {
        Matcher m = WORD.matcher(name);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(name, last, m.start());
            out.append(repairWord(m.group(), corpus, folded, counts));
            last = m.end();
        }
        out.append(name.substring(last));
        return out.toString();
    }

    private String repairWord(String word, Set<String> corpus, Set<String> folded, int[] counts) {
        if (word.indexOf(FFFD) < 0) return word;
        boolean lower = word.equals(word.toLowerCase()) && !word.equals(word.toUpperCase());
        String upper = word.toUpperCase();

        // gaps: positions of FFFD (upper and lower map to same index)
        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i < upper.length(); i++) if (upper.charAt(i) == FFFD) gaps.add(i);

        // Tier 1: try diacritic combinations (cap at 2 gaps -> <=25 combos) against corpus.
        if (gaps.size() <= 2) {
            String best = searchCombos(upper, gaps, 0, DIACRITICS, corpus);
            if (best != null) { counts[0]++; return lower ? best.toLowerCase() : best; }
            // Tier 2: ASCII base letters against the folded corpus.
            String base = searchCombos(upper, gaps, 0, BASES, folded);
            if (base != null) { counts[1]++; return lower ? base.toLowerCase() : base; }
        }

        // Tier 3: default every remaining gap to 'C'.
        counts[2]++;
        String def = upper.replace(FFFD, 'C');
        return lower ? def.toLowerCase() : def;
    }

    /** Recursively fill gaps with candidates; return the first filled word present in {@code dict}. */
    private String searchCombos(String word, List<Integer> gaps, int idx, char[] candidates, Set<String> dict) {
        if (idx == gaps.size()) {
            return dict.contains(word) ? word : null;
        }
        char[] chars = word.toCharArray();
        for (char c : candidates) {
            chars[gaps.get(idx)] = c;
            String hit = searchCombos(new String(chars), gaps, idx + 1, candidates, dict);
            if (hit != null) return hit;
        }
        return null;
    }

    private static final java.nio.charset.Charset WIN1250 =
            java.nio.charset.Charset.forName("windows-1250");

    /**
     * Recovers UTF-8 text that was decoded as Windows-1250 ("ÄŚOKOLADA" -> "ČOKOLADA").
     * Round-trips: re-encode to Windows-1250 bytes, decode as UTF-8. Lossless, but
     * only attempted when the corruption signature is present and the round-trip
     * stays representable and produces valid UTF-8 (no U+FFFD). Returns null if not
     * applicable, so clean strings are never touched.
     */
    String fixMojibake(String s) {
        // Signature: 0xC4/0xC5/0xE2 lead bytes show up as these chars under CP1250.
        if (s.indexOf('Ä') < 0 && s.indexOf('Ĺ') < 0
                && s.indexOf('â') < 0 && s.indexOf('Å') < 0) return null;
        if (!WIN1250.newEncoder().canEncode(s)) return null; // a char not in CP1250 -> unsafe
        String fixed = new String(s.getBytes(WIN1250), java.nio.charset.StandardCharsets.UTF_8);
        if (fixed.equals(s) || fixed.indexOf('�') >= 0) return null;
        return fixed;
    }

    /** Fold Croatian diacritics to their ASCII base letter (uppercase input). */
    private static String fold(String s) {
        return s.replace('Č', 'C').replace('Ć', 'C')
                .replace('Š', 'S').replace('Ž', 'Z').replace('Đ', 'D');
    }
}

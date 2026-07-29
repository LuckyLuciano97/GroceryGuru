package org.example.groceryguru.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps product names and search queries to canonical concepts ("jaja", "mlijeko",
 * "cokolada") so search can rank by what a product IS, not just which letters it
 * contains. Without this, "jaja" matches chocolate Easter eggs and egg-pasta just
 * as strongly as actual eggs.
 *
 * Rules live in concepts.tsv. Head-noun rules key off the first word (in Croatian
 * grocery names that is nearly always the product type); override rules reassign
 * when a stronger signal appears anywhere in the name, so "Cokoladna Jaja Oreo"
 * resolves to cokolada rather than jaja.
 */
@Service
public class ProductConceptService {

    private static final Logger log = LoggerFactory.getLogger(ProductConceptService.class);

    /** Head word -> concept. */
    private final Map<String, String> headWords = new HashMap<>();

    /** Override: any pattern present in the name reassigns, if head is in guard. */
    private record Override(String concept, List<String> patterns, List<String> guard) {}

    private final List<Override> overrides = new ArrayList<>();

    private static final Pattern HEAD = Pattern.compile("[^a-z]*([a-z]{3,})");

    public ProductConceptService() {
        load();
    }

    private void load() {
        try (InputStream in = getClass().getResourceAsStream("/concepts.tsv")) {
            if (in == null) {
                log.warn("concepts.tsv not found - concept ranking disabled");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                if ("H".equals(p[0])) {
                    for (String w : p[2].split("\\|")) {
                        if (!w.isBlank() && !w.contains("_")) headWords.put(w, p[1]);
                    }
                } else if ("O".equals(p[0])) {
                    List<String> pats = List.of(p[2].split("\\|"));
                    List<String> guard = p.length > 3 && !p[3].isBlank()
                            ? List.of(p[3].split("\\|")) : List.of();
                    overrides.add(new Override(p[1], pats, guard));
                }
            }
            log.info("Loaded {} concept head words, {} overrides", headWords.size(), overrides.size());
        } catch (Exception e) {
            log.warn("Could not load concepts.tsv: {}", e.getMessage());
        }
    }

    /** Diacritic-folded lowercase, matching the gg_fold() SQL function. */
    public static String fold(String s) {
        if (s == null) return "";
        String t = s.replace('đ', 'd').replace('Đ', 'D');
        t = Normalizer.normalize(t, Normalizer.Form.NFKD)
                      .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t.toLowerCase();
    }

    /** Concept of a product name, or null when no rule applies. */
    public String conceptOfName(String name) {
        String f = fold(name);
        Matcher m = HEAD.matcher(f);
        String head = m.lookingAt() ? m.group(1) : "";
        for (Override o : overrides) {
            if (!o.guard().isEmpty() && !o.guard().contains(head)) continue;
            for (String p : o.patterns()) {
                if (f.contains(p)) return o.concept();
            }
        }
        return headWords.get(head);
    }

    /**
     * Concept a search query is asking for, or null if it is not concept-shaped.
     * Prefix-aware so a partially typed query resolves too ("mlij" -> mlijeko).
     * With multiple query words the longest resolvable one wins, since Croatian
     * puts the product type in either position ("cokoladno mlijeko").
     */
    public String conceptOfQuery(String query) {
        String f = fold(query).trim();
        if (f.length() < 3) return null;

        String best = null;
        int bestLen = -1;
        for (String tok : f.split("[^a-z]+")) {
            if (tok.length() < 3) continue;
            String c = resolveToken(tok);
            if (c != null && tok.length() > bestLen) {
                best = c;
                bestLen = tok.length();
            }
        }
        return best;
    }

    /** Exact head word, else the shortest head word this token is a prefix of. */
    private String resolveToken(String tok) {
        String exact = headWords.get(tok);
        if (exact != null) return exact;

        String bestWord = null;
        for (Map.Entry<String, String> e : headWords.entrySet()) {
            String w = e.getKey();
            if (!w.startsWith(tok)) continue;
            // shortest completion is the closest to what was typed; alphabetical
            // tie-break keeps the choice deterministic
            if (bestWord == null || w.length() < bestWord.length()
                    || (w.length() == bestWord.length() && w.compareTo(bestWord) < 0)) {
                bestWord = w;
            }
        }
        return bestWord == null ? null : headWords.get(bestWord);
    }
}

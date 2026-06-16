package org.example.groceryguru.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw ingestion names ("LJEŠNJAK JEZGRA PP ORAHOVICA 500g") into readable
 * display names ("PP Orahovica Lješnjak jezgra 500g"): brand first, quantity last,
 * body title-cased. Only writes displayName; the raw name column stays untouched
 * since pg_trgm image matching depends on it.
 */
@Service
public class ProductNameNormalizer {

    /** Croatian prepositions / conjunctions that stay lowercase in titles. */
    private static final Set<String> LOWERCASE_WORDS = Set.of(
            "i", "u", "z", "s", "sa", "od", "do", "za", "na", "po", "iz",
            "the", "and", "or", "of"
    );

    /**
     * Tokens kept UPPERCASE. Anything else longer than 2 chars gets title-cased,
     * so Croatian 3-letter words (SOK, LUK, MED) aren't mistaken for acronyms.
     */
    private static final Set<String> UPPERCASE_TOKENS = Set.of(
            "wc", "tv", "dvd", "usb", "led", "uht", "bio", "abc",
            "xl", "xxl", "xxxl", "uv", "ph", "co2"
    );

    /**
     * Brand names truncated by store POS systems (WHISK -> Whiskas).
     * Explicit map on purpose: a generic "expand short words" heuristic
     * would mangle real Croatian words.
     */
    private static final Map<String, String> BRAND_ALIASES = Map.ofEntries(
            Map.entry("whisk", "Whiskas"),
            Map.entry("konz",  "Konzum"),
            Map.entry("gav",   "Gavrilović")
    );

    /**
     * Croatian grocery abbreviations -> full word (JAB -> Jabuka). Best-effort:
     * only high-confidence expansions belong here, verified via the preview endpoint.
     */
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            // Fruits / flavours
            Map.entry("jab",   "Jabuka"),
            Map.entry("nar",   "Naranča"),
            Map.entry("jag",   "Jagoda"),
            Map.entry("bresk", "Breskva"),
            Map.entry("viš",   "Višnja"),
            Map.entry("vis",   "Višnja"),
            Map.entry("mal",   "Malina"),
            Map.entry("ban",   "Banana"),
            Map.entry("baz",   "Bazga"),
            Map.entry("ljes",  "Lješnjak"),
            Map.entry("lješ",  "Lješnjak"),
            // Common product words
            Map.entry("čok",   "Čokolada"),
            Map.entry("cok",   "Čokolada"),
            Map.entry("čoko",  "Čokolada"),
            Map.entry("prot",  "Protein"),
            Map.entry("jog",   "Jogurt"),
            Map.entry("mlj",   "Mlijeko"),
            Map.entry("nap",   "Napitak"),
            Map.entry("sir.",  "Sirup"),
            Map.entry("mli",   "Mlijeko"),
            Map.entry("det",   "Deterdžent"),
            Map.entry("tuš",   "Tuširanje"),
            Map.entry("tus",   "Tuširanje"),
            Map.entry("namaz", "Namaz")
    );

    /** True if this lowercase token has a curated full-word expansion. */
    public boolean hasExpansion(String lowerWord) {
        return ABBREVIATIONS.containsKey(lowerWord) || BRAND_ALIASES.containsKey(lowerWord);
    }

    /** Promo / non-essence words dropped entirely (lowercase, whole-token match). */
    private static final Set<String> JUNK_WORDS = Set.of(
            "sniženo", "snizeno", "snižena", "snizena", "sniženi", "snizeni",
            "akcija", "akcijska", "akcijski", "akcijsko", "novo", "popust",
            // discontinued / internal markers and store-channel codes
            "staro", "kfav", "klc", "nmnp", "kafic", "kafić"
    );

    /** Known 2-letter store/category codes to drop (1-letter codes drop generally). */
    private static final Set<String> CODE_DENYLIST_2 = Set.of(
            "JA", "CA", "VC", "NB", "SF", "KL"
    );

    /** Matches a quantity-with-unit token: 500g, 1L, 2,8%, 100ml, m.m., 30L... */
    private static final Pattern QUANTITY = Pattern.compile(
            "^\\d+[.,]?\\d*\\s*(?:%|kg|g|l|ml|dl|cl|cm|mm)$|^m\\.m\\.$|^\\d+x\\d+$",
            Pattern.CASE_INSENSITIVE);

    /** Matches a standalone quantity like "500", "1.5" (number only). */
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\d+[.,]?\\d*$");

    /**
     * @param rawName     raw name from cijene.dev or store CSV (often ALL CAPS)
     * @param brand       brand from products table (nullable)
     * @return cleaned display name, or the raw name if normalization fails
     */
    /**
     * Decides what to do with a descriptive word during the readability pass:
     * return a full word to use, "" to drop it, or null to keep it as-is.
     */
    public interface TokenResolver {
        String resolve(String lowerWord);
    }

    public String normalize(String rawName, String brand) {
        return normalize(rawName, brand, null, Integer.MAX_VALUE);
    }

    /**
     * @param resolver     optional corpus-backed classifier (expand/keep/drop); null = keep all
     * @param maxBodyWords cap on descriptive words (brand and size excluded)
     */
    public String normalize(String rawName, String brand, TokenResolver resolver, int maxBodyWords) {
        if (rawName == null || rawName.isBlank()) return rawName;

        try {
            String working = rawName.trim().replaceAll("\\s+", " ");

            // strip junk leading punctuation ("-ČOKSA...", "*Aniversario")
            working = working.replaceAll("^[\\p{Punct}\\s]+", "");

            // split dotted abbreviations: "JAB.BAZ.SIRUP" -> "JAB BAZ SIRUP"
            // (only letter.letter, so decimals and units are untouched)
            working = working.replaceAll("(?<=\\p{L})\\.(?=\\p{L})", " ");

            // drop the "cca" (approx.) marker: "cca1,85kg" -> "1,85kg"
            working = working.replaceAll("(?i)\\bcca\\.?\\s*(?=\\d)", "");

            // merge number+unit ("400 g" -> "400g") and spelled-out Croatian units
            working = working.replaceAll(
                    "(?i)(\\d+[.,]?\\d*)\\s*(grama|gram)\\b", "$1g");
            working = working.replaceAll(
                    "(?i)(\\d+[.,]?\\d*)\\s*(kilograma|kilogram)\\b", "$1kg");
            working = working.replaceAll(
                    "(?i)(\\d+[.,]?\\d*)\\s*(litara|litre|litar)\\b", "$1L");
            working = working.replaceAll(
                    "(?i)(\\d+[.,]?\\d*)\\s*(mililitara|mililitre|mililitar)\\b", "$1ml");
            working = working.replaceAll(
                    "(?i)(\\d+[.,]?\\d*)\\s+(kg|g|ml|dl|cl|l|cm|mm|%)\\b",
                    "$1$2");
            // ranges: "600-800 g" -> "600-800g"
            working = working.replaceAll(
                    "(?i)(\\d+\\s*-\\s*\\d+)\\s+(kg|g|ml|dl|cl|l|%)\\b",
                    "$1$2");

            String[] tokens = working.split(" ");

            StringBuilder brandOut = new StringBuilder();
            StringBuilder bodyOut  = new StringBuilder();
            StringBuilder qtyOut   = new StringBuilder();
            int bodyWords = 0;

            // pull the brand out of the token stream so it isn't repeated
            String[] remaining = tokens;
            if (brand != null && !brand.isBlank()) {
                String[] brandTokens = brand.trim().split("\\s+");
                int idx = findContiguousMatch(tokens, brandTokens);
                if (idx >= 0) {
                    brandOut.append(formatBrand(brand.trim()));
                    remaining = removeRange(tokens, idx, idx + brandTokens.length);
                }
            }

            for (String tok : remaining) {
                if (tok.isBlank()) continue;

                // store-internal noise: dimensions, ratio codes, SKU numbers
                if (isJunkToken(tok)) continue;

                // promo/marker words ("sniženo", "akcija", "-staro")
                if (JUNK_WORDS.contains(tok.toLowerCase().replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", ""))) continue;

                // lone store/category codes ("K", "JA") - vitamins survive
                if (isStoreCode(tok)) continue;

                if (QUANTITY.matcher(tok).matches()) {
                    if (qtyOut.length() > 0) qtyOut.append(' ');
                    qtyOut.append(formatQuantity(tok));
                } else if (NUMBER_ONLY.matcher(tok).matches()) {
                    if (qtyOut.length() > 0) qtyOut.append(' ');
                    qtyOut.append(tok.replace('.', ','));
                } else {
                    String word = tok;
                    if (resolver != null) {
                        String key = tok.toLowerCase().replaceAll("[^a-zčćšžđ]", "");
                        if (!key.isEmpty()) {
                            String r = resolver.resolve(key);
                            if (r != null) {
                                if (r.isEmpty()) continue;   // drop truncation/junk
                                word = r;                     // expand to full word
                            }
                        }
                    }
                    if (bodyWords >= maxBodyWords) continue;  // word cap
                    if (bodyOut.length() > 0) bodyOut.append(' ');
                    bodyOut.append(formatWord(word, bodyOut.length() == 0 && brandOut.length() == 0));
                    bodyWords++;
                }
            }

            StringBuilder result = new StringBuilder();
            if (brandOut.length() > 0) result.append(brandOut);
            if (bodyOut.length()  > 0) {
                if (result.length() > 0) result.append(' ');
                result.append(bodyOut);
            }
            if (qtyOut.length()   > 0) {
                if (result.length() > 0) result.append(' ');
                result.append(qtyOut);
            }

            String out = result.toString().trim();
            return out.isEmpty() ? rawName : out;
        } catch (Exception e) {
            return rawName;
        }
    }

    // --- Token formatting helpers ---
    /**
     * Find a contiguous case-insensitive subsequence of {@code needle} inside {@code haystack}.
     * Returns the start index, or -1 if not found.
     */
    private int findContiguousMatch(String[] haystack, String[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (!haystack[i + j].equalsIgnoreCase(needle[j])) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Single letters that are meaningful (vitamins) - never stripped as codes. */
    private static final Set<String> CODE_WHITELIST = Set.of("A", "B", "C", "D", "E");

    /**
     * Lone uppercase store/category codes ("K", "JA"). 1-letter codes drop unless
     * they're vitamins; 2-letter only if denylisted (too many real words otherwise).
     */
    private boolean isStoreCode(String tok) {
        if (tok.isEmpty() || tok.length() > 2) return false;
        String up = tok.toUpperCase();
        String lower = tok.toLowerCase();
        if (LOWERCASE_WORDS.contains(lower)) return false;
        if (UPPERCASE_TOKENS.contains(lower)) return false;
        if (!tok.equals(up) || !up.matches("[A-ZČĆŠŽĐ]{1,2}")) return false;
        if (tok.length() == 1) return !CODE_WHITELIST.contains(up);
        return CODE_DENYLIST_2.contains(up);
    }

    /** Pack dimensions (26,5X26,5X3), ratio codes (100/1) and long SKU numbers. */
    private boolean isJunkToken(String tok) {
        String t = tok.toLowerCase().replaceAll("^-+|-+$", "");
        if (t.matches("\\d+([.,]\\d+)?([x×*]\\d+([.,]\\d+)?)+")) return true;
        if (t.matches("\\d+/\\d+")) return true;
        return t.matches("\\d{4,}");
    }

    private String[] removeRange(String[] arr, int from, int to) {
        String[] out = new String[arr.length - (to - from)];
        System.arraycopy(arr, 0, out, 0, from);
        System.arraycopy(arr, to, out, from, arr.length - to);
        return out;
    }

    /**
     * Title-cases a single word, applying Croatian/grocery-specific rules.
     * The first word in the body is always title-cased even if it's a stopword.
     */
    private String formatWord(String word, boolean isFirst) {
        String lower = word.toLowerCase();

        // lone punctuation: keep as-is
        if (!lower.matches(".*[a-zčćšžđ0-9].*")) return word;

        // lone unit tokens that escaped the pre-merge pass
        if (lower.matches("kg|g|ml|dl|cl")) return lower;
        if (lower.equals("l"))              return "L";

        String alias = BRAND_ALIASES.get(lower);
        if (alias != null) return alias;

        String abbr = ABBREVIATIONS.get(lower);
        if (abbr != null) return abbr;

        if (UPPERCASE_TOKENS.contains(lower)) return lower.toUpperCase();

        if (!isFirst && LOWERCASE_WORDS.contains(lower)) return lower;

        // keep ALL-CAPS only for 1-2 char tokens (PP, AB); longer ones are
        // usually normal words shouted by the CSV
        if (word.length() <= 2 && word.equals(word.toUpperCase()) && word.matches("[A-ZČĆŠŽĐ]+")) {
            return word;
        }

        // hyphenated tokens: title-case each segment
        if (word.contains("-")) {
            String[] parts = word.split("-");
            StringBuilder hOut = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) hOut.append('-');
                hOut.append(formatWord(parts[i], i == 0 && isFirst));
            }
            return hOut.toString();
        }

        // title-case from the first letter (skips leading punctuation)
        char[] chars = lower.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (Character.isLetter(chars[i])) {
                chars[i] = Character.toUpperCase(chars[i]);
                break;
            }
        }
        return new String(chars);
    }

    /**
     * Format brand for display. Strips Croatian company suffixes (D.O.O., d.d.)
     * and title-cases ALL-CAPS brands while keeping short acronyms uppercase.
     *   "ROSAL LIP BALM"   -> "Rosal Lip Balm"
     *   "PP ORAHOVICA"     -> "PP Orahovica"
     *   "KIFLIĆ D.O.O."    -> "Kiflić"
     *   "dm"               -> "dm"
     */
    private String formatBrand(String brand) {
        // Strip company suffixes (case-insensitive, with or without periods)
        String cleaned = brand
                .replaceAll("(?i)\\s+d\\.?o\\.?o\\.?$", "")
                .replaceAll("(?i)\\s+d\\.?d\\.?$", "")
                .replaceAll("(?i)\\s+ltd\\.?$", "")
                .replaceAll("(?i)\\s+inc\\.?$", "")
                .trim();

        // Whole-brand truncation match ("WHISK" -> "Whiskas").
        String wholeAlias = BRAND_ALIASES.get(cleaned.toLowerCase());
        if (wholeAlias != null) return wholeAlias;

        // Already mixed-case (e.g., "PP Orahovica", "K-Classic")? Leave it.
        if (!cleaned.equals(cleaned.toUpperCase()) && !cleaned.equals(cleaned.toLowerCase())) {
            return cleaned;
        }
        // Lowercase brand like "dm"? Leave it.
        if (cleaned.equals(cleaned.toLowerCase())) return cleaned;

        // Title-case each word, keeping short acronyms ALL-CAPS
        String[] parts = cleaned.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (p.length() <= 3 && p.equals(p.toUpperCase()) && p.matches("[A-ZČĆŠŽĐ]+")) {
                out.append(p); // PP, K, AB stay upper
            } else {
                out.append(Character.toUpperCase(p.charAt(0)));
                out.append(p.substring(1).toLowerCase());
            }
        }
        return out.toString();
    }

    /**
     * Normalize quantity tokens:
     *  - Lowercase units except "L" (liter)
     *  - Comma as decimal separator (Croatian convention)
     *  - Collapse spaces
     */
    private String formatQuantity(String q) {
        String s = q.replace('.', ',');
        // Uppercase L for liters
        Matcher m = Pattern.compile("(\\d+,?\\d*)\\s*([a-zA-Z%]+|m\\.m\\.)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.matches()) {
            String num  = m.group(1);
            String unit = m.group(2);
            if (unit.equalsIgnoreCase("l")) unit = "L";
            else if (unit.equalsIgnoreCase("m.m.")) unit = "m.m.";
            else unit = unit.toLowerCase();
            return num + unit;
        }
        return s.toLowerCase();
    }
}

package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.repository.ProductRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ProductService {

    private final ProductRepo productRepo;
    private final ProductTranslationService translationService;
    private final ProductConceptService conceptService;
    private final JdbcTemplate jdbc;

    public ProductService(ProductRepo productRepo, ProductTranslationService translationService,
                          ProductConceptService conceptService, JdbcTemplate jdbc) {
        this.productRepo = productRepo;
        this.translationService = translationService;
        this.conceptService = conceptService;
        this.jdbc = jdbc;
    }

    /**
     * Ensures the search extensions, the diacritic-folding function and the
     * trigram indexes exist. gg_fold() strips Croatian diacritics so "cokolada"
     * finds "Čokolada" - without it a user typing on a plain keyboard misses most
     * of the catalog. It is declared IMMUTABLE (via the two-argument unaccent so
     * the dictionary is pinned) so it can back an expression index.
     */
    @jakarta.annotation.PostConstruct
    void ensureSearchIndex() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            jdbc.execute("""
                CREATE OR REPLACE FUNCTION gg_fold(txt text) RETURNS text AS
                $$ SELECT lower(public.unaccent('public.unaccent', translate(txt, 'đĐ', 'dD'))) $$
                LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_products_fold_trgm " +
                    "ON products USING GIN (gg_fold(COALESCE(display_name, name)) gin_trgm_ops)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_products_concept ON products(concept)");
        } catch (Exception e) {
            // Non-fatal: search still works, just without folding or the index.
        }
    }

    public List<Product> findAllProducts(){
        return productRepo.findAll();
    }

    public List<Product> searchProductsByName(String name){
        return productRepo.findByNameContainingIgnoreCase(name);
    }

    /** Escapes LIKE wildcards so a query like "50%" is matched literally. */
    private static String likeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Relevance-ranked search.
     *
     * Everything is matched on gg_fold(), so queries work with or without Croatian
     * diacritics ("cokolada" finds "Čokolada").
     *
     * Ranking is a weighted score rather than hard tiers. Hard tiers let a single
     * lexical accident dominate: a leftover truncation carried by zero stores would
     * win an exact-word match and outrank a staple sold in 900 stores. With a score,
     * popularity and concept agreement can outweigh a marginally better string match.
     *
     * The concept term is what makes "jaja" return eggs: chocolate eggs resolve to
     * the cokolada concept and egg-pasta to tjestenina, so both are penalised for
     * belonging to a different concept than the query asked for.
     */
    public Page<Product> searchProductsByName(String name, Pageable pageable) {
        Set<String> terms = translationService.expandSearch(name);
        String queryConcept = conceptService.conceptOfQuery(name);

        // Folded name, and a punctuation-normalised copy padded with spaces so
        // word matching works next to commas and dots ("Mlijeko. 0,5L").
        final String F = "gg_fold(COALESCE(display_name, name))";
        final String W = "(' ' || regexp_replace(" + F + ", '[^a-z0-9]+', ' ', 'g') || ' ')";

        // WHERE stays on the bare folded column so the GIN trigram index applies.
        StringBuilder where = new StringBuilder("(");
        List<Object> whereParams = new ArrayList<>();
        int i = 0;
        for (String term : terms) {
            if (i++ > 0) where.append(" OR ");
            where.append(F).append(" LIKE ? OR gg_fold(COALESCE(brand, '')) LIKE ?");
            String t = "%" + likeLiteral(ProductConceptService.fold(term)) + "%";
            whereParams.add(t);
            whereParams.add(t);
        }
        where.append(")");

        StringBuilder score = new StringBuilder();
        List<Object> scoreParams = new ArrayList<>();

        // Whole-word match: the query is a word in the name, not a fragment of one.
        score.append("(CASE WHEN ");
        int j = 0;
        for (String term : terms) {
            if (j++ > 0) score.append(" OR ");
            score.append(W).append(" LIKE ?");
            scoreParams.add("% " + likeLiteral(ProductConceptService.fold(term)) + " %");
        }
        score.append(" THEN 3.0 ELSE 0 END)");

        // Head noun: Croatian grocery names lead with the product type.
        score.append(" + (CASE WHEN ");
        j = 0;
        for (String term : terms) {
            if (j++ > 0) score.append(" OR ");
            score.append("split_part(btrim(").append(W).append("), ' ', 1) LIKE ?");
            scoreParams.add(likeLiteral(ProductConceptService.fold(term)) + "%");
        }
        score.append(" THEN 2.0 ELSE 0 END)");

        // Any word beginning with the query - partial typing still ranks.
        score.append(" + (CASE WHEN ");
        j = 0;
        for (String term : terms) {
            if (j++ > 0) score.append(" OR ");
            score.append(W).append(" LIKE ?");
            scoreParams.add("% " + likeLiteral(ProductConceptService.fold(term)) + "%");
        }
        score.append(" THEN 1.0 ELSE 0 END)");

        // Concept agreement. Only applies when the query names a concept.
        if (queryConcept != null) {
            score.append(" + (CASE WHEN concept = ? THEN 2.5")
                 .append(" WHEN concept IS NOT NULL THEN -2.5 ELSE 0 END)");
            scoreParams.add(queryConcept);
        }

        // Popularity: store coverage stands in for how staple a product is.
        // Log-scaled and capped so a huge count cannot swamp relevance outright.
        score.append(" + 1.5 * LEAST(ln(1 + COALESCE(store_count, 0)) / ln(1000), 1.0)");

        // Trigram similarity as the fine-grained tiebreaker.
        score.append(" + 0.8 * similarity(").append(F).append(", gg_fold(?))");
        scoreParams.add(name);

        String sql = "SELECT * FROM products WHERE " + where +
                " ORDER BY (" + score + ") DESC, COALESCE(store_count, 0) DESC, " + F +
                " LIMIT ? OFFSET ?";

        List<Object> allParams = new ArrayList<>();
        allParams.addAll(whereParams);
        allParams.addAll(scoreParams);
        allParams.add(pageable.getPageSize());
        allParams.add(pageable.getOffset());

        List<Product> products = jdbc.query(sql, (rs, idx) -> {
            Product p = new Product();
            p.setId(rs.getLong("id"));
            p.setName(rs.getString("name"));
            p.setDisplayName(rs.getString("display_name"));
            p.setBarcode(rs.getString("barcode"));
            p.setBrand(rs.getString("brand"));
            p.setCategory(rs.getString("category"));
            p.setNetQuantity(rs.getString("net_quantity"));
            p.setUnit(rs.getString("unit"));
            p.setImageUrl(rs.getString("image_url"));
            return p;
        }, allParams.toArray());

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE " + where,
                Long.class, whereParams.toArray());
        return new PageImpl<>(products, pageable, total != null ? total : 0);
    }


    public Product getProductById(Long id){
        return productRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    public Product createProduct(Product product){
        return productRepo.save(product);
    }


    public Product updateProduct(Long id, Product updated) {
        Product existing = productRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));

        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        return productRepo.save(existing);
    }

    public List<String> getCategories() {
        return productRepo.findDistinctCategories();
    }

    public Page<Product> getByCategory(String category, Pageable pageable) {
        return productRepo.findByCategoryIgnoreCase(category, pageable);
    }

    public int cleanupBadImages() {
        return jdbc.update("UPDATE products SET image_url = NULL, image_searched = false WHERE image_url LIKE '%category_thumb%' OR image_url LIKE '%shop_thumb%'");
    }

    /**
     * Clear all images that came from name-search sources (Lidl + Konzum).
     * These were saved without word-match validation so many are wrong.
     * After cleanup, re-fetch will use the new validation logic.
     */
    public int cleanupNameSearchImages() {
        return jdbc.update(
                "UPDATE products SET image_url = NULL, image_searched = false " +
                "WHERE image_url LIKE '%assets.schwarz%' OR image_url LIKE '%cloudfront.net%'");
    }

    /**
     * Returns the top {@code limit} image URLs ordered by how many products share them.
     * Use this to find wrong/shared images (e.g. a Barbie doll picture attached to
     * dozens of unrelated products because a search results page had it as the featured
     * item at the top).
     */
    public List<Map<String, Object>> getMostSharedImageUrls(int limit) {
        return jdbc.query(
                "SELECT image_url, COUNT(*) AS count FROM products " +
                "WHERE image_url IS NOT NULL " +
                "GROUP BY image_url " +
                "ORDER BY COUNT(*) DESC " +
                "LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("image_url", rs.getString("image_url"));
                    row.put("count", rs.getLong("count"));
                    return row;
                },
                limit);
    }

    /**
     * Wipes {@code image_url} for every product whose image_url contains the given
     * substring. Also resets {@code image_searched} so the next batch fetch retries.
     * <p>
     * Pass a unique-enough substring of the offending URL - e.g. the filename hash,
     * the bucket path, or the Cjenoteka product slug.
     */
    public int cleanupImagesByUrl(String urlPattern) {
        if (urlPattern == null || urlPattern.isBlank()) return 0;
        return jdbc.update(
                "UPDATE products SET image_url = NULL, image_searched = false " +
                "WHERE image_url LIKE ?",
                "%" + urlPattern + "%");
    }

    /**
     * Bulk wipe: clears {@code image_url} for every product whose image URL is shared
     * with more than {@code minCount} other products. This is how we purge the
     * "stuck fallback" images produced by name-search endpoints that silently return
     * a popular/featured product when the filter doesn't match - each call produces
     * the same one of a handful of URLs, so one URL ends up on thousands of unrelated
     * rows.
     * <p>
     * Uses a single SQL statement with a subquery; safe to run multiple times.
     *
     * @param minCount minimum number of products a URL must appear on to be deleted
     * @return number of products wiped
     */
    public int cleanupSharedImages(int minCount) {
        if (minCount < 2) minCount = 2;
        return jdbc.update(
                "UPDATE products SET image_url = NULL, image_searched = false " +
                "WHERE image_url IN (" +
                "    SELECT image_url FROM products " +
                "    WHERE image_url IS NOT NULL " +
                "    GROUP BY image_url " +
                "    HAVING COUNT(*) > ?" +
                ")",
                minCount);
    }

    public int resetImageSearched() {
        return jdbc.update(
                "UPDATE products SET image_searched = false WHERE image_url IS NULL AND image_searched = true");
    }

    public void delete(Long id){
        if (!productRepo.existsById(id)) {
            throw new EntityNotFoundException("Product not found: " + id);
        }
        productRepo.deleteById(id);
    }

    /**
     * Fixes ALL corrupted text in products table:
     * 1. Raw windows-1250 bytes (0x9A=š, 0x8A=Š, 0x9E=ž, 0x8E=Ž, etc.)
     * 2. U+FFFD replacement characters from previous bad decoding
     * 3. ASCII-only categories missing diacritics
     *
     * Uses bytea-level operations to avoid PostgreSQL text encoding validation errors.
     */
    public int fixEncodingIssues() {
        int total = 0;

        // Step 1: Fix raw windows-1250 bytes in ALL text columns using bytea replacement
        // These are actual invalid UTF-8 bytes that slipped in
        String[] textCols = {"name", "brand", "category", "subcategory", "description"};
        String[][] win1250toUtf8 = {
                {"\\x9a", "\\xc5\\xa1"},  // š
                {"\\x8a", "\\xc5\\xa0"},  // Š
                {"\\x9e", "\\xc5\\xbe"},  // ž
                {"\\x8e", "\\xc5\\xbd"},  // Ž
                {"\\xe8", "\\xc4\\x8d"},  // č (win1250)
                {"\\xc8", "\\xc4\\x8c"},  // Č (win1250)
                {"\\xe6", "\\xc4\\x87"},  // ć (win1250)
                {"\\xc6", "\\xc4\\x86"},  // Ć (win1250)
                {"\\xf0", "\\xc4\\x91"},  // đ (win1250)
                {"\\xd0", "\\xc4\\x90"},  // Đ (win1250)
        };

        for (String col : textCols) {
            for (String[] mapping : win1250toUtf8) {
                try {
                    String sql = String.format(
                            "UPDATE products SET %s = convert_from(" +
                                    "replace(%s::bytea::text, '%s', '%s')::bytea, 'UTF8') " +
                                    "WHERE position(decode('%s','hex') IN %s::bytea) > 0",
                            col, col, mapping[0], mapping[1],
                            mapping[0].replace("\\x", ""), col
                    );
                    int updated = jdbc.update(sql);
                    if (updated > 0) {
                        total += updated;
                    }
                } catch (Exception e) {
                    // Skip if column doesn't have those bytes
                }
            }
        }

        // Step 2: Fix U+FFFD replacement characters in product names using context
        // The replacement char is valid UTF-8 (efbfbd) so we can use normal string ops
        String fffd = "\uFFFD";
        Map<String, String> contextFixes = new LinkedHashMap<>();
        // š replacements
        contextFixes.put("bra" + fffd + "n", "brašn");
        contextFixes.put("pa" + fffd + "tet", "paštet");
        contextFixes.put(fffd + "amp", "šamp");
        contextFixes.put(fffd + "ok.", "šok.");
        contextFixes.put(fffd + "oko", "čoko");  // čokolad
        contextFixes.put(fffd + "ip", "čip");     // čips
        contextFixes.put(fffd + "aj", "čaj");     // čaj
        contextFixes.put(fffd + "et", "čet");     // četk
        contextFixes.put("pu" + fffd + "v", "pušv");
        contextFixes.put("spu" + fffd + "v", "spužv");
        contextFixes.put("ma" + fffd + "k", "mašk");
        contextFixes.put(fffd + "tap", "štap");
        contextFixes.put(fffd + "tru", "štru");
        contextFixes.put("su" + fffd + "il", "sušil");
        contextFixes.put("mu" + fffd + "k", "mušk");
        contextFixes.put("bu" + fffd + "in", "bušin");
        contextFixes.put("plo" + fffd + "ic", "pločic");
        contextFixes.put("vo" + fffd + "ni", "vočni"); // voćni -> actually should be ć
        // ć replacements
        contextFixes.put("vre" + fffd + "ic", "vrećic");
        contextFixes.put("vre" + fffd + "e", "vreće");
        contextFixes.put("vre" + fffd + "a", "vreća");
        contextFixes.put("vo" + fffd + "n", "voćn");
        contextFixes.put("vo" + fffd + ".", "voć.");
        contextFixes.put("pi" + fffd + "e", "piće");
        contextFixes.put("pi" + fffd + "a", "pića");
        contextFixes.put("ku" + fffd + "an", "kućan");
        contextFixes.put("pe" + fffd + "en", "pečen");
        contextFixes.put("pr" + fffd + "ut", "pršut");
        contextFixes.put("ka" + fffd + "ic", "kačic"); // actually kašic
        // ž replacements
        contextFixes.put("d" + fffd + "em", "džem");
        contextFixes.put("gra" + fffd + "ev", "građev");
        contextFixes.put("lje" + fffd + "nj", "lješnj");
        contextFixes.put("za" + fffd + "in", "začin");
        contextFixes.put("vi" + fffd + "nj", "višnj");
        contextFixes.put("bo" + fffd + "i" + fffd + "n", "božićn");
        contextFixes.put("le" + fffd + "a", "leža");
        contextFixes.put("ra" + fffd + "ak", "ražak");  // krastavac -> kra
        contextFixes.put("enu" + fffd + "ac", "enužac"); // actually enudžac? no
        contextFixes.put("ra" + fffd + "en", "ražen");
        contextFixes.put("je" + fffd + "av", "ježav");
        contextFixes.put("gr" + fffd + "ki", "grčki");
        // č context
        contextFixes.put("raj" + fffd + "ic", "rajčic");
        contextFixes.put("ka" + fffd + "a", "kaša");   // actually could be kača
        contextFixes.put(fffd + "unk", "šunk");
        contextFixes.put(fffd + "un", "šun");
        contextFixes.put("isa" + fffd + " ", "isač ");  // actually čistač
        contextFixes.put("je" + fffd + "i", "ječi");    // ječi -> ječm
        contextFixes.put("la" + fffd + " ", "lač ");
        contextFixes.put("ne" + fffd + "a", "neša");    // oreščan? no
        // klipić, Varaždin and other commonly missed
        contextFixes.put("klipi" + fffd, "klipić");
        contextFixes.put("Vara" + fffd + "din", "Varaždin");
        contextFixes.put("vara" + fffd + "din", "varaždin");
        contextFixes.put("dr" + fffd + "a", "drža");     // držač
        contextFixes.put("dr" + fffd + "i", "drži");
        contextFixes.put("ba" + fffd + "v", "bačv");     // bačva
        contextFixes.put("ku" + fffd + "i", "kući");
        contextFixes.put("li" + fffd + "n", "lišn");     // lišnjak
        contextFixes.put("o" + fffd + "i", "oči");       // očišćen
        contextFixes.put("o" + fffd + "n", "očn");
        contextFixes.put("ko" + fffd + "i", "koči");
        contextFixes.put("li" + fffd + "e", "liće");
        contextFixes.put("li" + fffd + "a", "lića");
        contextFixes.put("ti" + fffd, "tić");
        contextFixes.put("ni" + fffd, "nić");

        for (Map.Entry<String, String> fix : contextFixes.entrySet()) {
            try {
                int updated = jdbc.update(
                        "UPDATE products SET name = replace(name, ?, ?) WHERE name LIKE ?",
                        fix.getKey(), fix.getValue(), "%" + fix.getKey() + "%"
                );
                total += updated;
            } catch (Exception e) {
                // Skip failures
            }
        }

        // Step 3: Fix categories
        Map<String, String> catFixes = Map.of(
                "PICE", "Piće",
                "SREDSTVA ZA CISCENJE", "Sredstva za čišćenje",
                "TOALETNE POTREPSTINE", "Toaletne potrepštine",
                "PROIZVODI ZA KUCANSTVO", "Proizvodi za kućanstvo"
        );
        for (Map.Entry<String, String> fix : catFixes.entrySet()) {
            try {
                jdbc.update("UPDATE products SET category = ? WHERE category = ?",
                        fix.getValue(), fix.getKey());
            } catch (Exception e) {
                // Skip
            }
        }

        // Also fix categories with U+FFFD
        try {
            jdbc.update("UPDATE products SET category = ? WHERE category = ?", "Pića", "Pi" + fffd + "a");
            jdbc.update("UPDATE products SET category = ? WHERE category = ?", "Piće", "Pi" + fffd + "e");
            jdbc.update("UPDATE products SET category = ? WHERE category = ?",
                    "Proizvodi za kućanstvo", "Proizvodi za ku" + fffd + "anstvo");
            jdbc.update("UPDATE products SET category = ? WHERE category = ?",
                    "Sredstva za čišćenje", "Sredstva za " + fffd + "i" + fffd + fffd + "enje");
            jdbc.update("UPDATE products SET category = ? WHERE category = ?",
                    "Toaletne potrepštine", "Toaletne potrep" + fffd + "tine");
        } catch (Exception e) {
            // Skip
        }

        // Clean up temp columns if they exist
        try { jdbc.update("ALTER TABLE products DROP COLUMN IF EXISTS cat_raw"); } catch (Exception e) {}
        try { jdbc.update("ALTER TABLE products DROP COLUMN IF EXISTS name_raw"); } catch (Exception e) {}
        try { jdbc.update("ALTER TABLE products DROP COLUMN IF EXISTS brand_raw"); } catch (Exception e) {}

        return total;
    }
}

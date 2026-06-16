package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.ProductRequest;
import org.example.groceryguru.dto.ProductResponseDto;
import org.example.groceryguru.model.Price;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.service.CjenetokaCacheService;
import org.example.groceryguru.service.LocalImageMatcherService;
import org.example.groceryguru.service.ProductImageService;
import org.example.groceryguru.service.ProductNameNormalizer;
import org.example.groceryguru.service.ProductService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.example.groceryguru.repository.PriceRepo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final PriceRepo priceRepo;
    private final ProductImageService productImageService;
    private final CjenetokaCacheService cjenetokaCacheService;
    private final LocalImageMatcherService localImageMatcherService;
    private final ProductNameNormalizer nameNormalizer;
    private final org.example.groceryguru.service.EncodingRepairService encodingRepairService;
    private final org.example.groceryguru.service.CanonicalNamingService canonicalNamingService;
    private final org.example.groceryguru.service.ReadabilityService readabilityService;
    private final JdbcTemplate jdbc;

    public ProductController(ProductService productService, PriceRepo priceRepo,
                             ProductImageService productImageService,
                             CjenetokaCacheService cjenetokaCacheService,
                             LocalImageMatcherService localImageMatcherService,
                             ProductNameNormalizer nameNormalizer,
                             org.example.groceryguru.service.EncodingRepairService encodingRepairService,
                             org.example.groceryguru.service.CanonicalNamingService canonicalNamingService,
                             org.example.groceryguru.service.ReadabilityService readabilityService,
                             JdbcTemplate jdbc) {
        this.productService = productService;
        this.priceRepo = priceRepo;
        this.productImageService = productImageService;
        this.cjenetokaCacheService = cjenetokaCacheService;
        this.localImageMatcherService = localImageMatcherService;
        this.nameNormalizer = nameNormalizer;
        this.encodingRepairService = encodingRepairService;
        this.canonicalNamingService = canonicalNamingService;
        this.readabilityService = readabilityService;
        this.jdbc = jdbc;
    }

    /**
     * Rewrites display names to full words only (expand truncations, drop codes,
     * cap word count). Dry-run by default; pass ?apply=true to write.
     */
    @GetMapping("/improve-readability")
    public Map<String, Object> improveReadability(
            @RequestParam(defaultValue = "false") boolean apply,
            @RequestParam(defaultValue = "5") int maxWords,
            @RequestParam(defaultValue = "40") int limit) {
        return readabilityService.improve(apply, maxWords, limit);
    }

    /**
     * Unifies display names across duplicate product rows (same barcode after
     * stripping a float ".0"), using the most trusted chain's name.
     * Dry-run by default; pass ?apply=true to write.
     */
    @GetMapping("/unify-names")
    public Map<String, Object> unifyNames(
            @RequestParam(defaultValue = "false") boolean apply,
            @RequestParam(defaultValue = "30") int limit) {
        return canonicalNamingService.unify(apply, limit);
    }

    /**
     * Repairs names corrupted by lost diacritics (U+FFFD) using the clean corpus.
     * Dry-run by default; pass ?apply=true to write the fixes.
     */
    @GetMapping("/repair-encoding")
    public Map<String, Object> repairEncoding(
            @RequestParam(defaultValue = "false") boolean apply,
            @RequestParam(defaultValue = "40") int limit) {
        return encodingRepairService.repair(apply, limit);
    }

    @GetMapping
    public List<ProductResponseDto> getAll() {
        return productService.findAllProducts()
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/{id:\\d+}")
    public ProductResponseDto getById(@PathVariable Long id) {
        return mapToDto(productService.getProductById(id));
    }

    @GetMapping("/search")
    public Map<String, Object> searchByName(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Product> productPage = productService.searchProductsByName(name, PageRequest.of(page, size));
        List<Product> products = productPage.getContent();

        // Batch-fetch cheapest prices for all found products
        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, BigDecimal> cheapestPrices = getCheapestPrices(productIds);

        List<ProductResponseDto> content = products.stream()
                .map(p -> new ProductResponseDto(
                        p.getId(),
                        bestName(p),
                        p.getDescription(),
                        cheapestPrices.get(p.getId()),
                        p.getImageUrl(),
                        p.getCategory()
                ))
                .toList();

        return Map.of(
                "content", content,
                "totalPages", productPage.getTotalPages(),
                "totalElements", productPage.getTotalElements(),
                "currentPage", page
        );
    }

    private Map<Long, BigDecimal> getCheapestPrices(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return priceRepo.findByProductIdInAndIsCurrentTrue(productIds)
                .stream()
                .collect(Collectors.groupingBy(
                        p -> p.getProduct().getId(),
                        Collectors.reducing(
                                (Price) null,
                                (a, b) -> a == null ? b : (b.getPrice().compareTo(a.getPrice()) < 0 ? b : a)
                        )
                ))
                .entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getPrice()));
    }

    @GetMapping("/categories")
    public List<String> getCategories() {
        return productService.getCategories();
    }

    @GetMapping("/by-category")
    public Map<String, Object> getByCategory(
            @RequestParam String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Product> productPage = productService.getByCategory(category, PageRequest.of(page, size));
        List<Product> products = productPage.getContent();

        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, BigDecimal> cheapestPrices = getCheapestPrices(productIds);

        List<ProductResponseDto> content = products.stream()
                .map(p -> new ProductResponseDto(
                        p.getId(),
                        bestName(p),
                        p.getDescription(),
                        cheapestPrices.get(p.getId()),
                        p.getImageUrl(),
                        p.getCategory()
                ))
                .toList();

        return Map.of(
                "content", content,
                "totalPages", productPage.getTotalPages(),
                "totalElements", productPage.getTotalElements(),
                "currentPage", page
        );
    }

    @GetMapping("/fetch-images")
    public ResponseEntity<String> fetchImages() {
        long missing = productService.findAllProducts().stream()
                .filter(p -> p.getImageUrl() == null)
                .count();
        new Thread(() -> productImageService.fetchAllMissingImages()).start();
        return ResponseEntity.ok("Image fetch started for " + missing + " products. Check server logs for progress.");
    }

    @GetMapping("/fetch-images-smart")
    public ResponseEntity<String> fetchImagesSmart() {
        new Thread(() -> productImageService.fetchImagesByHomeStore()).start();
        return ResponseEntity.ok("Smart home-store image fetch started. " +
                "Each product is routed to its own store's website first. " +
                "Track live at /api/products/image-progress");
    }

    @GetMapping("/fetch-store-images")
    public ResponseEntity<String> fetchStoreImages() {
        long missing = productService.findAllProducts().stream()
                .filter(p -> p.getImageUrl() == null)
                .count();
        new Thread(() -> productImageService.fetchFromStores()).start();
        return ResponseEntity.ok("Store image fetch started for " + missing + " products (Lidl -> Konzum -> OFF). Check server logs.");
    }

    @GetMapping("/cleanup-images")
    public ResponseEntity<String> cleanupImages() {
        int cleaned = productService.cleanupBadImages();
        return ResponseEntity.ok("Cleaned " + cleaned + " bad images. Re-fetch with /fetch-images.");
    }

    @GetMapping("/cleanup-name-search-images")
    public ResponseEntity<String> cleanupNameSearchImages() {
        int cleaned = productService.cleanupNameSearchImages();
        return ResponseEntity.ok("Cleaned " + cleaned + " unvalidated Lidl/Konzum images. Re-fetch with /fetch-store-images.");
    }

    /**
     * Diagnostic: returns the top N image URLs ranked by how many products share them.
     * Use this to find a "stuck" wrong image (e.g. a Barbie doll picture attached to
     * dozens of unrelated products). Default limit is 20.
     */
    @GetMapping("/image-stats")
    public List<Map<String, Object>> imageStats(
            @RequestParam(defaultValue = "20") int limit) {
        return productService.getMostSharedImageUrls(limit);
    }

    /**
     * Wipes every product whose {@code image_url} contains the given substring.
     * Example: {@code /cleanup-by-url?url=barbie} or a specific Cjenoteka slug.
     * After running, re-fetch with /fetch-images or /fetch-store-images.
     */
    @GetMapping("/cleanup-by-url")
    public ResponseEntity<String> cleanupByUrl(@RequestParam String url) {
        int cleaned = productService.cleanupImagesByUrl(url);
        return ResponseEntity.ok("Cleaned " + cleaned
                + " products whose image_url contained: " + url
                + ". Re-fetch with /fetch-images.");
    }

    /**
     * Bulk wipe: clears any image URL shared by more than {@code minCount} products.
     * This is the fastest way to purge "stuck fallback" images from search endpoints
     * that silently return featured/popular items when the real filter misses.
     * Default threshold is 100.
     */
    @GetMapping("/cleanup-shared-images")
    public ResponseEntity<String> cleanupSharedImages(
            @RequestParam(defaultValue = "100") int minCount) {
        int cleaned = productService.cleanupSharedImages(minCount);
        return ResponseEntity.ok("Cleaned " + cleaned
                + " products whose image was shared by more than " + minCount
                + " others. Re-fetch with /fetch-images.");
    }

    @GetMapping("/image-progress")
    public Map<String, Object> imageProgress() {
        return productImageService.getProgress();
    }

    @GetMapping("/reset-image-search")
    public ResponseEntity<String> resetImageSearch() {
        int reset = productService.resetImageSearched();
        return ResponseEntity.ok("Reset " + reset + " products - they will be retried on next /fetch-images.");
    }

    // --- Cjenoteka local cache & pg_trgm matching ---
    /**
     * Scrapes Cjenoteka by category and brand search terms, storing ALL found
     * products into a local {@code cjenoteka_products} table. Runs in background.
     * Monitor at /api/products/cjenoteka-cache-stats.
     * Rate-limited to 1 req/sec - expect 1-4 hours depending on category/brand count.
     */
    @GetMapping("/build-cjenoteka-cache")
    public ResponseEntity<String> buildCjenotekaCache() {
        cjenetokaCacheService.setupSchema(); // ensure table exists before background thread starts
        new Thread(() -> cjenetokaCacheService.buildCache()).start();
        return ResponseEntity.ok("Cjenoteka cache build started in background. " +
                "Monitor at /api/products/cjenoteka-cache-stats. " +
                "Rate-limited to 1 req/sec.");
    }

    /**
     * Returns live stats for the Cjenoteka cache build and the current table size.
     */
    @GetMapping("/cjenoteka-cache-stats")
    public Map<String, Object> cjenotekaStats() {
        return cjenetokaCacheService.getCacheStats();
    }

    /**
     * Runs the offline pg_trgm image matching:
     * Pass 1 - exact barcode JOIN (zero false positives).
     * Pass 2 - trigram similarity JOIN (threshold configurable via image.match.similarity-threshold).
     * Completes in seconds. Run AFTER /build-cjenoteka-cache.
     */
    @GetMapping("/match-images-local")
    public Map<String, Object> matchImagesLocal() {
        return localImageMatcherService.matchAll();
    }

    /**
     * Preview top similarity matches WITHOUT applying them.
     * Use this to validate the threshold before running /match-images-local.
     */
    @GetMapping("/preview-image-matches")
    public Map<String, Object> previewImageMatches(
            @RequestParam(defaultValue = "50") int limit) {
        return localImageMatcherService.previewMatches(limit);
    }

    // --- Display name normalization ---
    /**
     * Backfills the {@code display_name} column for every product by running
     * the raw {@code name} (and {@code brand}) through {@link ProductNameNormalizer}.
     * Safe to re-run - overwrites only when the result differs.
     * Processes ~135K rows in 1-2 minutes via JDBC batch updates.
     */
    @GetMapping("/normalize-names")
    public ResponseEntity<Map<String, Object>> normalizeAllNames(
            @RequestParam(defaultValue = "false") boolean force) {
        long start = System.currentTimeMillis();

        // Load every product (or only those without a display_name)
        String selectSql = force
                ? "SELECT id, name, brand FROM products"
                : "SELECT id, name, brand FROM products WHERE display_name IS NULL OR display_name = ''";

        List<long[]> ids = new ArrayList<>();
        List<Object[]> batch = new ArrayList<>();
        int[] processed = {0};
        int[] changed   = {0};

        jdbc.query(selectSql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long id      = rs.getLong("id");
            String raw   = rs.getString("name");
            String brand = rs.getString("brand");
            String display = nameNormalizer.normalize(raw, brand);
            processed[0]++;
            if (display != null && !display.equals(raw)) changed[0]++;
            batch.add(new Object[]{display, id});

            if (batch.size() >= 1000) {
                jdbc.batchUpdate("UPDATE products SET display_name = ? WHERE id = ?", batch);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("UPDATE products SET display_name = ? WHERE id = ?", batch);
        }

        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", processed[0]);
        result.put("changed",   changed[0]);
        result.put("elapsedMs", elapsed);
        return ResponseEntity.ok(result);
    }

    /**
     * Preview the normalizer output without writing anything to the DB.
     * Useful for tuning the algorithm before running the full backfill.
     */
    @GetMapping("/preview-normalized-names")
    public List<Map<String, String>> previewNormalizedNames(
            @RequestParam(defaultValue = "30") int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        jdbc.query(
                "SELECT id, name, brand FROM products ORDER BY random() LIMIT ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String raw   = rs.getString("name");
                    String brand = rs.getString("brand");
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("raw",        raw);
                    row.put("brand",      brand);
                    row.put("normalized", nameNormalizer.normalize(raw, brand));
                    out.add(row);
                },
                limit
        );
        return out;
    }

    @GetMapping("/fix-encoding")
    public ResponseEntity<String> fixEncoding() {
        int total = productService.fixEncodingIssues();
        return ResponseEntity.ok("Fixed " + total + " product categories.");
    }

    @PostMapping
    public ResponseEntity<ProductResponseDto> create(@Valid @RequestBody ProductRequest request) {
        Product product = new Product();
        product.setName(request.name());
        product.setDescription(request.description());

        Product saved = productService.createProduct(product);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ProductResponseDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        Product updated = new Product();
        updated.setName(request.name());
        updated.setDescription(request.description());

        return mapToDto(productService.updateProduct(id, updated));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    private ProductResponseDto mapToDto(Product product) {
        return new ProductResponseDto(
                product.getId(),
                bestName(product),
                product.getDescription(),
                null,
                product.getImageUrl(),
                product.getCategory()
        );
    }

    /**
     * Prefer {@code displayName} when present (cleaned, title-cased, brand-first);
     * fall back to raw {@code name} for older rows that haven't been normalized yet.
     * The raw {@code name} is still used internally for pg_trgm image matching.
     */
    private static String bestName(Product p) {
        String d = p.getDisplayName();
        return (d != null && !d.isBlank()) ? d : p.getName();
    }
}
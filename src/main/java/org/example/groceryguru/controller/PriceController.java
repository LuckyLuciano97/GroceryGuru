package org.example.groceryguru.controller;

import org.example.groceryguru.dto.PriceResponseDto;
import org.example.groceryguru.dto.PriceUpdateRequest;
import org.example.groceryguru.model.Price;
import org.example.groceryguru.service.PriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;
    private final JdbcTemplate jdbc;

    public PriceController(PriceService priceService, JdbcTemplate jdbc) {
        this.priceService = priceService;
        this.jdbc = jdbc;
    }

    @GetMapping("/product/{productId}")
    public List<PriceResponseDto> getCurrentPricesForProduct(@PathVariable Long productId) {
        return priceService.findByProductIdAndIsCurrentTrue(productId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/product/{productId}/by-chain")
    public List<ChainPriceDto> getPricesByChain(@PathVariable Long productId) {
        return priceService.findByProductIdAndIsCurrentTrue(productId)
                .stream()
                .filter(p -> p.getStore().getChain() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getStore().getChain().getName(),
                        Collectors.minBy(Comparator.comparing(p -> p.getPrice()))
                ))
                .entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .map(e -> {
                    var price = e.getValue().get();
                    return new ChainPriceDto(
                            e.getKey(),
                            price.getStore().getChain().getId(),
                            price.getPrice(),
                            price.getStore().getName(),
                            price.getStore().getCity(),
                            price.isOnSale(),
                            price.getRegularPrice()
                    );
                })
                .sorted(Comparator.comparing(ChainPriceDto::cheapestPrice))
                .toList();
    }

    public record ChainPriceDto(
            String chainName,
            Long chainId,
            BigDecimal cheapestPrice,
            String cheapestStoreName,
            String cheapestStoreCity,
            boolean onSale,
            BigDecimal regularPrice
    ) {}

    @GetMapping("/product/{productId}/cheapest")
    public PriceResponseDto getCheapestPrice(@PathVariable Long productId){
        Price price = priceService.findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(productId);
        return mapToDto(price);
    }

    @GetMapping("/products/batch")
    public List<PriceResponseDto> getCurrentPricesForManyProducts(@RequestParam List<Long> productIds){
        return priceService.getCurrentPricesForProducts(productIds)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @PostMapping("/product/{productId}/store/{storeId}")
    public ResponseEntity<PriceResponseDto> upsertCurrentProductInStore(@PathVariable Long productId, @PathVariable Long storeId, @Valid @RequestBody PriceUpdateRequest request){
        Price price = priceService.upsertPrice(productId, storeId, request.value());
                return ResponseEntity.ok(mapToDto(price));
    }

    @GetMapping("/cleanup-zero-prices")
    public ResponseEntity<String> cleanupZeroPrices() {
        int deleted = jdbc.update("DELETE FROM prices WHERE price <= 0");
        return ResponseEntity.ok("Deleted " + deleted + " prices with value <= 0.");
    }

    @GetMapping("/stats")
    public Map<String, Object> priceStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPrices", jdbc.queryForObject("SELECT COUNT(*) FROM prices", Long.class));
        stats.put("currentPrices", jdbc.queryForObject("SELECT COUNT(*) FROM prices WHERE is_current = true", Long.class));
        stats.put("zeroPrices", jdbc.queryForObject("SELECT COUNT(*) FROM prices WHERE price <= 0", Long.class));
        stats.put("productsWithPrices", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT product_id) FROM prices WHERE is_current = true AND price > 0", Long.class));
        stats.put("productsTotal", jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class));
        return stats;
    }

    private PriceResponseDto mapToDto(Price price) {
        return new PriceResponseDto(
                price.getId(),
                price.getProduct().getId(),
                price.getProduct().getName(),
                price.getStore().getId(),
                price.getStore().getName(),
                price.getPrice());

    }
}

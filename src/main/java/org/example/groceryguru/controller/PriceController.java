package org.example.groceryguru.controller;

import org.example.groceryguru.dto.PriceResponseDto;
import org.example.groceryguru.dto.PriceUpdateRequest;
import org.example.groceryguru.model.Price;
import org.example.groceryguru.service.PriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;


    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/product/{productId}")
    public List<PriceResponseDto> getCurrentPricesForProduct(@PathVariable Long productId) {
        return priceService.findByProductIdAndIsCurrentTrue(productId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/product/{productId}/cheapest")
    public PriceResponseDto getCheapestPrice(@PathVariable Long productId){
        Price price = priceService.findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(productId);
        return mapToDto(price);
    }

    @GetMapping("/product/{productIds}")
    public List<PriceResponseDto> getCurrentPricesForManyProducts(@PathVariable List<Long> productIds){
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

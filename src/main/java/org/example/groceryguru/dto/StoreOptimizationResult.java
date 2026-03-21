package org.example.groceryguru.dto;

import java.math.BigDecimal;
import java.util.List;

public record StoreOptimizationResult(
        Long storeId,
        String storeName,
        String storeCity,
        BigDecimal totalPrice,
        List<ItemPriceDetail> items,
        boolean complete,
        List<String> missingProducts
) {}
package org.example.groceryguru.dto;

import java.math.BigDecimal;
import java.util.List;

public record StoreOptimizationResult(
        Long storeId,
        String chainName,
        String city,
        BigDecimal totalPrice,
        List<ItemPriceDetail> items,
        boolean complete,
        List<String> missingProducts,
        // For chains missing an exact item: cheapest name-equivalent the user can
        // tap to add (hybrid "exact, then offer"). Not counted in totalPrice.
        List<ItemPriceDetail> suggestions
) {}

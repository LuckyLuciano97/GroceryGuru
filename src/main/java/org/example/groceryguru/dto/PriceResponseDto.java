package org.example.groceryguru.dto;

import java.math.BigDecimal;

public record PriceResponseDto(
        Long id,
        Long productId,
        String productName,
        Long storeId,
        String storeName,
        BigDecimal price
) {}
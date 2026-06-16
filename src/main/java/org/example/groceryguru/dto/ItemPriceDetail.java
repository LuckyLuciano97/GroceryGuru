package org.example.groceryguru.dto;

import java.math.BigDecimal;

public record ItemPriceDetail(
        Long productId,
        String productName,
        String searchTerm,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {}
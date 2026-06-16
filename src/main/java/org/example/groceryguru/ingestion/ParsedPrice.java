package org.example.groceryguru.ingestion;

import java.math.BigDecimal;

public record ParsedPrice(
        String productName,
        String productCode,
        String barcode,
        String brand,
        String netQuantity,
        String unit,
        String category,
        BigDecimal retailPrice,
        BigDecimal salePrice,
        String storeCode,
        String storeName,
        String storeStreet,
        String storeCity,
        int postalCode
) {}

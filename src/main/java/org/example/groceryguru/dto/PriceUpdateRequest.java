package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PriceUpdateRequest(
        @NotNull(message = "Price value is required")
        @Positive(message = "Price must be positive")
        BigDecimal value
) {
}

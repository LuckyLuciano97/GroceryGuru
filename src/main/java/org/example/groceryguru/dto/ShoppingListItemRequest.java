package org.example.groceryguru.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ShoppingListItemRequest(
        @NotNull(message = "Product ID is required") Long productId,
        @Min(value = 1, message = "Quantity must be at least 1") Integer quantity
) {}
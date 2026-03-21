package org.example.groceryguru.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ShoppingListItemRequest(
        @NotNull Long productId,
        @Min(1) Integer quantity
) {}
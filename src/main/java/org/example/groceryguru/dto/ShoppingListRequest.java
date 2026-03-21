package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ShoppingListRequest(
        @NotBlank String name,
        @NotNull Long userId
) {}
package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ShoppingListRequest(
        @NotBlank(message = "List name is required") String name,
        @NotNull(message = "User ID is required") Long userId
) {}
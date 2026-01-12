package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoreChainRequestDto(
        @NotBlank(message = "Chain name is required")
        @Size(max = 100, message = "Chain name must not exceed 100 characters")
        String name,

        String websiteUrl,

        @Size(max = 2, message = "Country code must be 2 characters")
        String countryCode
) {}
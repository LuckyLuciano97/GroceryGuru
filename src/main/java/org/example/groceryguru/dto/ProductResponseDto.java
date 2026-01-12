package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.groceryguru.model.Price;

import java.math.BigDecimal;

public record ProductResponseDto (
        Long id,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 600) String description,
        BigDecimal cheapestCurrentPrice
) {}
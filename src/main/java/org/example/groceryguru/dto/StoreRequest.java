package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record StoreRequest(
        @NotBlank(message = "Store name is required")
        @Size(max = 100, message = "Store name must not exceed 100 characters")
        String name,
        
        Long chainId,
        
        String street,
        
        String city,
        
        Integer postalCode,
        
        LocalTime opensAt,
        
        LocalTime closesAt
) {}

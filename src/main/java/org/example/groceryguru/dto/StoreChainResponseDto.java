package org.example.groceryguru.dto;

public record StoreChainResponseDto(
        Long id,
        String name,
        String websiteUrl,
        String countryCode
) {}

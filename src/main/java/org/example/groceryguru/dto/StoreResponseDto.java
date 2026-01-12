package org.example.groceryguru.dto;

import java.time.LocalTime;

public record StoreResponseDto(
        Long id,
        String name,
        String street,
        String city,
        int postalCode,
        LocalTime opensAt,
        LocalTime closesAt,
        Long chainId,
        String chainName
) {}
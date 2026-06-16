package org.example.groceryguru.dto;

import java.time.LocalTime;

/**
 * DTO for nearby stores response, includes distance from user location.
 */
public record NearbyStoreDto(
        Long id,
        String name,
        String city,
        LocalTime opensAt,
        LocalTime closesAt,
        Double latitude,
        Double longitude,
        Double distanceKm,
        String chainName,
        Double minPrice  // cheapest product price at this store
) {}

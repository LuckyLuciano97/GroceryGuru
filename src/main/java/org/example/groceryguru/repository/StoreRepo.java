package org.example.groceryguru.repository;

import org.example.groceryguru.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepo extends JpaRepository<Store, Long> {
    List<Store> findByCityContainingIgnoreCase(String cityName);

    Optional<Store> findByStoreCode(String storeCode);

    @Query("SELECT DISTINCT s.city FROM Store s WHERE LOWER(s.city) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY s.city")
    List<String> findDistinctCities(String query);

    /**
     * Find stores within a given radius from latitude/longitude using Haversine distance formula.
     * Returns stores sorted by distance (closest first).
     *
     * @param latitude User latitude
     * @param longitude User longitude
     * @param radiusKm Search radius in kilometers
     * @param city Optional city filter (uppercase)
     * @return List of stores with id, name, city, hours, lat/lon, distance, chain name
     */
    @Query(value = """
            SELECT
                s.id,
                s.name,
                s.city,
                s.opens_at,
                s.closes_at,
                s.latitude,
                s.longitude,
                (6371 * 2 * ASIN(SQRT(
                    POW(SIN(RADIANS((:latitude - s.latitude) / 2)), 2) +
                    COS(RADIANS(s.latitude)) * COS(RADIANS(:latitude)) *
                    POW(SIN(RADIANS((:longitude - s.longitude) / 2)), 2)
                ))) AS distance_km,
                sc.name AS chain_name,
                COALESCE(MIN(p.price), NULL) AS min_price
            FROM stores s
            LEFT JOIN store_chains sc ON s.chain_id = sc.id
            LEFT JOIN prices p ON p.store_id = s.id AND p.is_current = true
            WHERE s.latitude IS NOT NULL
                AND s.longitude IS NOT NULL
                AND (COALESCE(:city, '') = '' OR UPPER(s.city) = UPPER(:city))
                AND (6371 * 2 * ASIN(SQRT(
                    POW(SIN(RADIANS((:latitude - s.latitude) / 2)), 2) +
                    COS(RADIANS(s.latitude)) * COS(RADIANS(:latitude)) *
                    POW(SIN(RADIANS((:longitude - s.longitude) / 2)), 2)
                ))) <= :radiusKm
            GROUP BY s.id, s.name, s.city, s.opens_at, s.closes_at, s.latitude, s.longitude, sc.name
            ORDER BY distance_km ASC
            """, nativeQuery = true)
    List<Object[]> findNearbyStores(Double latitude, Double longitude, Double radiusKm, String city);
}

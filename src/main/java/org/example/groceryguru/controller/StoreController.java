package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.NearbyStoreDto;
import org.example.groceryguru.dto.StoreRequest;
import org.example.groceryguru.dto.StoreResponseDto;
import org.example.groceryguru.model.Store;
import org.example.groceryguru.model.StoreChain;
import org.example.groceryguru.repository.StoreRepo;
import org.example.groceryguru.service.GeocodingService;
import org.example.groceryguru.service.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private static final Logger log = LoggerFactory.getLogger(StoreController.class);

    private final StoreService storeService;
    private final StoreRepo storeRepo;
    private final GeocodingService geocodingService;

    public StoreController(StoreService storeService, StoreRepo storeRepo, GeocodingService geocodingService) {
        this.storeService = storeService;
        this.storeRepo = storeRepo;
        this.geocodingService = geocodingService;
    }

    @GetMapping
    public List<StoreResponseDto> getAll() {
        return storeService.getAllStores()
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/{id}")
    public StoreResponseDto getById(@PathVariable Long id) {
        Store store = storeService.getStoreById(id);
        return mapToDto(store);
    }

    @GetMapping("/search")
    public List<StoreResponseDto> searchByCity(@RequestParam String city) {
        return storeService.findStoresByCity(city)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/cities")
    public List<String> searchCities(@RequestParam String query) {
        return storeService.findDistinctCities(query);
    }

    @GetMapping("/nearby")
    public List<NearbyStoreDto> getNearbyStores(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(defaultValue = "25") Double radiusKm,
            @RequestParam(required = false) String city) {
        List<Object[]> results = storeRepo.findNearbyStores(latitude, longitude, radiusKm, city);
        return results.stream()
                .map(row -> new NearbyStoreDto(
                        ((Number) row[0]).longValue(),           // id
                        (String) row[1],                         // name
                        (String) row[2],                         // city
                        (LocalTime) row[3],                      // opensAt
                        (LocalTime) row[4],                      // closesAt
                        ((Number) row[5]).doubleValue(),         // latitude
                        ((Number) row[6]).doubleValue(),         // longitude
                        ((Number) row[7]).doubleValue(),         // distanceKm
                        (String) row[8],                         // chainName
                        row[9] != null ? ((Number) row[9]).doubleValue() : null  // minPrice
                ))
                .toList();
    }

    @GetMapping("/geocode-all")
    public ResponseEntity<String> geocodeAllStores() {
        List<Store> ungeocodedStores = storeRepo.findAll().stream()
                .filter(s -> s.getLatitude() == null && s.getCity() != null)
                .toList();

        log.info("Enqueuing manual geocoding for {} stores via shared rate-limited worker",
                ungeocodedStores.size());
        for (Store store : ungeocodedStores) {
            Long id = store.getId();
            String name = store.getName();
            geocodingService.geocodeAsync(
                    store.getStreet(), store.getCity(), store.getPostalCode(),
                    (result, error) -> {
                        if (error != null) {
                            log.debug("Geocoding error for {} ({}): {}", name, id, error.getMessage());
                            return;
                        }
                        if (result != null) {
                            try {
                                storeRepo.findById(id).ifPresent(s -> {
                                    s.setLatitude(result.latitude());
                                    s.setLongitude(result.longitude());
                                    storeRepo.save(s);
                                });
                            } catch (Exception e) {
                                log.debug("Failed to save coordinates for {}: {}", name, e.getMessage());
                            }
                        }
                    }
            );
        }

        return ResponseEntity.ok("Geocoding enqueued for " + ungeocodedStores.size()
                + " stores (1 req/sec - will take ~" + (ungeocodedStores.size() * 1100 / 1000)
                + "s). Check server logs for progress.");
    }

    @PostMapping
    public ResponseEntity<StoreResponseDto> create(@Valid @RequestBody StoreRequest request) {
        Store store = new Store();
        store.setName(request.name());
        store.setStreet(request.street());
        store.setCity(request.city());
        store.setPostalCode(request.postalCode());
        store.setOpensAt(request.opensAt());
        store.setClosesAt(request.closesAt());

        if (request.chainId() != null) {
            StoreChain chain = new StoreChain();
            chain.setId(request.chainId());
            store.setChain(chain);
        }

        Store saved = storeService.createStore(store);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public StoreResponseDto update(@PathVariable Long id, @Valid @RequestBody StoreRequest request) {
        Store updated = new Store();
        updated.setName(request.name());
        updated.setStreet(request.street());
        updated.setCity(request.city());
        updated.setPostalCode(request.postalCode());
        updated.setOpensAt(request.opensAt());
        updated.setClosesAt(request.closesAt());

        if (request.chainId() != null) {
            StoreChain chain = new StoreChain();
            chain.setId(request.chainId());
            updated.setChain(chain);
        }

        return mapToDto(storeService.updateStore(id, updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        storeService.deleteStore(id);
        return ResponseEntity.noContent().build();
    }

    private StoreResponseDto mapToDto(Store store) {
        StoreChain chain = store.getChain();

        Long chainId = (chain == null) ? null : chain.getId();
        String chainName = (chain == null) ? null : chain.getName();

        return new StoreResponseDto(
                store.getId(),
                store.getName(),
                store.getStreet(),
                store.getCity(),
                store.getPostalCode(),
                store.getOpensAt(),
                store.getClosesAt(),
                chainId,
                chainName
        );
    }
}
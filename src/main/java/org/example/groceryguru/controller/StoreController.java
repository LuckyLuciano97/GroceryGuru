package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.StoreRequestDto;
import org.example.groceryguru.dto.StoreResponseDto;
import org.example.groceryguru.model.Store;
import org.example.groceryguru.model.StoreChain;
import org.example.groceryguru.service.StoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
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

    // If you already have repo/service support for city filtering, keep this.
    // If not, comment it out until you add: StoreRepo.findByCityIgnoreCase(...) and StoreService.findByCity(...)
    @GetMapping("/search")
    public List<StoreResponseDto> searchByCity(@RequestParam String city) {
        return storeService.findStoresByCity(city)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<StoreResponseDto> create(@Valid @RequestBody StoreRequestDto request) {
        Store store = new Store();
        store.setName(request.name());
        store.setStreet(request.street());
        store.setCity(request.city());
        store.setPostalCode(request.postalCode());
        store.setOpensAt(request.opensAt());
        store.setClosesAt(request.closesAt());

        // Set chain if provided
        if (request.chainId() != null) {
            StoreChain chain = new StoreChain();
            chain.setId(request.chainId());
            store.setChain(chain);
        }

        Store saved = storeService.createStore(store);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public StoreResponseDto update(@PathVariable Long id, @Valid @RequestBody StoreRequestDto request) {
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
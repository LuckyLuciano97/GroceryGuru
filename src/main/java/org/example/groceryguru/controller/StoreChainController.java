package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.StoreChainRequestDto;
import org.example.groceryguru.dto.StoreChainResponseDto;
import org.example.groceryguru.model.StoreChain;
import org.example.groceryguru.service.StoreChainService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/store-chains")
public class StoreChainController {

    private final StoreChainService storeChainService;

    public StoreChainController(StoreChainService storeChainService) {
        this.storeChainService = storeChainService;
    }

    @GetMapping
    public List<StoreChainResponseDto> getAll() {
        return storeChainService.findAll()
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/{id}")
    public StoreChainResponseDto getById(@PathVariable Long id) {
        StoreChain chain = storeChainService.getById(id);
        return mapToDto(chain);
    }

    @PostMapping
    public ResponseEntity<StoreChainResponseDto> create(@Valid @RequestBody StoreChainRequestDto request) {
        StoreChain chain = new StoreChain();
        chain.setName(request.name());
        chain.setWebsiteUrl(request.websiteUrl());
        chain.setCountryCode(request.countryCode());

        StoreChain saved = storeChainService.create(chain);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @GetMapping("/by-name")
    public StoreChainResponseDto getByName(@RequestParam String name) {
        StoreChain chain = storeChainService.findByName(name);
        return mapToDto(chain);
    }

    private StoreChainResponseDto mapToDto(StoreChain chain) {
        return new StoreChainResponseDto(
                chain.getId(),
                chain.getName(),
                chain.getWebsiteUrl(),
                chain.getCountryCode()
        );
    }
}
package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.StoreChain;
import org.example.groceryguru.repository.StoreChainRepo;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StoreChainService {

    private final StoreChainRepo storeChainRepo;


    public StoreChainService(StoreChainRepo storeChainRepo) {
        this.storeChainRepo = storeChainRepo;
    }

    public StoreChain findByName(String name){
        return storeChainRepo.findByName(name)
                .orElseThrow(() -> new EntityNotFoundException("Store chain not found + " + name));


    }

}

package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.Store;
import org.example.groceryguru.repository.StoreRepo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreService {

    private final StoreRepo storeRepo;

    public StoreService(StoreRepo storeRepo) {
        this.storeRepo = storeRepo;
    }

    public List<Store> getAllStores() {
        return storeRepo.findAll();
    }

    public List<Store> findStoresByCity(String city) {
        return storeRepo.findByCityContainingIgnoreCase(city);
    }

    public Store getStoreById(Long id) {
        return storeRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Store not found: " + id));
    }

    public Store createStore(Store store) {
        return storeRepo.save(store);
    }

    public Store updateStore(Long id, Store updated) {
        Store existing = storeRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Store not found: " + id));

        existing.setChain(updated.getChain());
        existing.setName(updated.getName());
        existing.setStreet(updated.getStreet());
        existing.setCity(updated.getCity());
        existing.setPostalCode(updated.getPostalCode());
        existing.setOpensAt(updated.getOpensAt());
        existing.setClosesAt(updated.getClosesAt());

        return storeRepo.save(existing);
    }

    public void deleteStore(Long id) {
        if (!storeRepo.existsById(id)) {
            throw new EntityNotFoundException("Store not found: " + id);
        }
        storeRepo.deleteById(id);
    }
}

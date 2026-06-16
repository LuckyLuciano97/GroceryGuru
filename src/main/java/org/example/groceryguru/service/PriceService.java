package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.Price;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.model.Store;
import org.example.groceryguru.repository.PriceRepo;
import org.example.groceryguru.repository.ProductRepo;
import org.example.groceryguru.repository.StoreRepo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PriceService {

    private final PriceRepo priceRepo;
    private final ProductRepo productRepo;
    private final StoreRepo storeRepo;

    public PriceService(PriceRepo priceRepo, ProductRepo productRepo, StoreRepo storeRepo) {
        this.priceRepo = priceRepo;
        this.productRepo = productRepo;
        this.storeRepo = storeRepo;
    }

    public List<Price> findByProductIdAndIsCurrentTrue(Long productId){
        return priceRepo.findByProductIdAndIsCurrentTrue(productId);
    }

    public List<Price> getCurrentPricesForProducts(List<Long> productIds){
        if(productIds == null || productIds.isEmpty()){
    return List.of();
        }
        return priceRepo.findByProductIdInAndIsCurrentTrue(productIds);
    }

    public Price upsertPrice(Long productId, Long storeId, BigDecimal value) {
        Product existingProduct = productRepo.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));

        Store existingStore = storeRepo.findById(storeId)
                .orElseThrow(() -> new EntityNotFoundException("Store not found " + storeId));

        Optional<Price> existingPriceInStore = priceRepo
                .findByProductIdAndStoreIdAndIsCurrentTrue(productId, storeId)
                .stream().findFirst();

        Price price;
        if(existingPriceInStore.isPresent()){
            price = existingPriceInStore.get();
            price.setPrice(value);
            price.setTimestamp(Instant.now());
        } else {
            price = new Price();
            price.setPrice(value);
            price.setStore(existingStore);
            price.setProduct(existingProduct);
            price.setCurrent(true);
            price.setTimestamp(Instant.now());
        }
        return priceRepo.save(price);
    }

    public Price findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(Long productId){
        Price price = priceRepo.findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(productId);
        if (price == null){
            throw new EntityNotFoundException("No current prices for product: " + productId);
        }
        return price;
    }


}

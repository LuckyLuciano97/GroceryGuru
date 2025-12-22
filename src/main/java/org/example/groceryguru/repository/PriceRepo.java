package org.example.groceryguru.repository;

import org.example.groceryguru.model.Price;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceRepo extends JpaRepository<Price, Long> {
    List<Price> findCurrentPricesByProductId(Long productId);
    List<Price> getCurrentPricesForProducts(List<Long> productIds);
   // Price getCheapestCurrentPriceForProduct(Long productId);
    Price findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(Long productId);
    List<Price> findByProductIdAndStoreIdAndIsCurrentTrue(Long productId, Long storeId);


}

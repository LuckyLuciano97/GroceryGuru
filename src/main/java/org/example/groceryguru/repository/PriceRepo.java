package org.example.groceryguru.repository;

import org.example.groceryguru.model.Price;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceRepo extends JpaRepository<Price, Long> {
    List<Price> findCurrentPricesByProductId(Long id);
    List<Price> findByProductIdAndIsCurrentTrue(Long productId);
    Optional<Price> findFirstByProductIdAndIsCurrentTrueOrderByPriceAsc(Long productId);


}

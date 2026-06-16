package org.example.groceryguru.repository;

import org.example.groceryguru.model.LoyaltyCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyCardRepo extends JpaRepository<LoyaltyCard, Long> {

    List<LoyaltyCard> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<LoyaltyCard> findByIdAndUserId(Long id, Long userId);
}

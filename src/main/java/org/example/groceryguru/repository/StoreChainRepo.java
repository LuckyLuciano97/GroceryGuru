package org.example.groceryguru.repository;

import org.example.groceryguru.model.StoreChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface StoreChainRepo extends JpaRepository<StoreChain, Long> {
    Optional<StoreChain> findByName(String name);

}

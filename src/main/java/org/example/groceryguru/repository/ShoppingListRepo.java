package org.example.groceryguru.repository;

import org.example.groceryguru.model.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShoppingListRepo extends JpaRepository<ShoppingList, Long> {
    List<ShoppingList> findByUserId(Long userId);
}

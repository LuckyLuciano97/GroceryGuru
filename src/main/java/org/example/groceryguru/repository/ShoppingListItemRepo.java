package org.example.groceryguru.repository;

import org.example.groceryguru.model.ShoppingListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShoppingListItemRepo extends JpaRepository<ShoppingListItem, Long> {
    List<ShoppingListItem> findByShoppingListId(Long shoppingListId);
}
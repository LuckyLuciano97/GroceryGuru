package org.example.groceryguru.repository;

import org.example.groceryguru.model.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShoppingListRepo extends JpaRepository<ShoppingList, Long> {

    List<ShoppingList> findByUserId(Long userId);

    @Query("SELECT DISTINCT sl FROM ShoppingList sl " +
           "LEFT JOIN sl.members m " +
           "WHERE sl.user.id = :userId OR m.user.id = :userId " +
           "ORDER BY sl.createdAt DESC")
    List<ShoppingList> findOwnedOrShared(@Param("userId") Long userId);
}

package org.example.groceryguru.repository;

import org.example.groceryguru.model.ShoppingListMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingListMemberRepo extends JpaRepository<ShoppingListMember, Long> {

    List<ShoppingListMember> findByShoppingListId(Long shoppingListId);

    List<ShoppingListMember> findByUserId(Long userId);

    Optional<ShoppingListMember> findByShoppingListIdAndUserId(Long shoppingListId, Long userId);

    boolean existsByShoppingListIdAndUserId(Long shoppingListId, Long userId);
}

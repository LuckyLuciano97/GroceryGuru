package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.ShoppingListItemRequest;
import org.example.groceryguru.dto.ShoppingListRequest;
import org.example.groceryguru.dto.StoreOptimizationResult;
import org.example.groceryguru.model.ShoppingList;
import org.example.groceryguru.model.ShoppingListItem;
import org.example.groceryguru.service.ShoppingListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shopping-lists")
public class ShoppingListController {

    private final ShoppingListService shoppingListService;

    public ShoppingListController(ShoppingListService shoppingListService) {
        this.shoppingListService = shoppingListService;
    }

    // Get all lists for a user
    @GetMapping("/user/{userId}")
    public List<ShoppingList> getListsForUser(@PathVariable Long userId) {
        return shoppingListService.getListsForUser(userId);
    }

    // Create a new list
    @PostMapping
    public ResponseEntity<ShoppingList> createList(@Valid @RequestBody ShoppingListRequest request) {
        ShoppingList list = shoppingListService.createList(request.name(), request.userId());
        return ResponseEntity.ok(list);
    }

    // Add item to list
    @PostMapping("/{listId}/items")
    public ResponseEntity<ShoppingListItem> addItem(
            @PathVariable Long listId,
            @Valid @RequestBody ShoppingListItemRequest request) {
        ShoppingListItem item = shoppingListService.addItem(listId, request.productId(), request.quantity());
        return ResponseEntity.ok(item);
    }

    // Remove item from list
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long itemId) {
        shoppingListService.removeItem(itemId);
        return ResponseEntity.noContent().build();
    }

    // Delete a list
    @DeleteMapping("/{listId}")
    public ResponseEntity<Void> deleteList(@PathVariable Long listId) {
        shoppingListService.deleteList(listId);
        return ResponseEntity.noContent().build();
    }

    // THE MAIN FEATURE - optimize the list
    @GetMapping("/{listId}/optimize")
    public List<StoreOptimizationResult> optimize(@PathVariable Long listId) {
        return shoppingListService.optimize(listId);
    }
}
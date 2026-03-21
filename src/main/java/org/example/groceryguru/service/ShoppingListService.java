package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.dto.ItemPriceDetail;
import org.example.groceryguru.dto.StoreOptimizationResult;
import org.example.groceryguru.model.*;
import org.example.groceryguru.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ShoppingListService {

    private final ShoppingListRepo shoppingListRepo;
    private final ShoppingListItemRepo shoppingListItemRepo;
    private final ProductRepo productRepo;
    private final UserRepo userRepo;
    private final PriceRepo priceRepo;

    public ShoppingListService(ShoppingListRepo shoppingListRepo,
                               ShoppingListItemRepo shoppingListItemRepo,
                               ProductRepo productRepo,
                               UserRepo userRepo,
                               PriceRepo priceRepo) {
        this.shoppingListRepo = shoppingListRepo;
        this.shoppingListItemRepo = shoppingListItemRepo;
        this.productRepo = productRepo;
        this.userRepo = userRepo;
        this.priceRepo = priceRepo;
    }

    // Get all lists for a user
    public List<ShoppingList> getListsForUser(Long userId) {
        return shoppingListRepo.findByUserId(userId);
    }

    // Create a new shopping list
    public ShoppingList createList(String name, Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        ShoppingList list = new ShoppingList();
        list.setName(name);
        list.setUser(user);
        return shoppingListRepo.save(list);
    }

    // Add a product to a list
    public ShoppingListItem addItem(Long listId, Long productId, Integer quantity) {
        ShoppingList list = shoppingListRepo.findById(listId)
                .orElseThrow(() -> new EntityNotFoundException("List not found: " + listId));
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        ShoppingListItem item = new ShoppingListItem();
        item.setShoppingList(list);
        item.setProduct(product);
        item.setQuantity(quantity);
        return shoppingListItemRepo.save(item);
    }

    // Remove an item from a list
    public void removeItem(Long itemId) {
        if (!shoppingListItemRepo.existsById(itemId))
            throw new EntityNotFoundException("Item not found: " + itemId);
        shoppingListItemRepo.deleteById(itemId);
    }

    // Delete a list
    public void deleteList(Long listId) {
        if (!shoppingListRepo.existsById(listId))
            throw new EntityNotFoundException("List not found: " + listId);
        shoppingListRepo.deleteById(listId);
    }

    // THE MAIN ALGORITHM - find cheapest store for the whole list
    public List<StoreOptimizationResult> optimize(Long listId) {
        List<ShoppingListItem> items = shoppingListItemRepo.findByShoppingListId(listId);

        if (items.isEmpty()) throw new IllegalStateException("Shopping list is empty");

        Map<Long, List<ItemPriceDetail>> storeItemMap = new HashMap<>();
        Map<Long, Store> storeRegistry = new HashMap<>();

        for (ShoppingListItem item : items) {
            List<Price> prices = priceRepo.findByProductIdAndIsCurrentTrue(item.getProduct().getId());

            for (Price price : prices) {
                Store store = price.getStore();
                storeRegistry.put(store.getId(), store);

                ItemPriceDetail detail = new ItemPriceDetail(
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        price.getPrice(),
                        price.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                );

                storeItemMap.computeIfAbsent(store.getId(), k -> new ArrayList<>()).add(detail);
            }
        }

        // Build a map of productId -> productName for missing product detection
        Map<Long, String> allProducts = new HashMap<>();
        for (ShoppingListItem item : items) {
            allProducts.put(item.getProduct().getId(), item.getProduct().getName());
        }

        List<StoreOptimizationResult> results = new ArrayList<>();

        for (Map.Entry<Long, List<ItemPriceDetail>> entry : storeItemMap.entrySet()) {
            Store store = storeRegistry.get(entry.getKey());
            List<ItemPriceDetail> storeItems = entry.getValue();

            // Find which products are missing at this store
            Set<Long> coveredProductIds = new HashSet<>();
            for (ItemPriceDetail detail : storeItems) {
                coveredProductIds.add(detail.productId());
            }

            List<String> missingProducts = allProducts.entrySet().stream()
                    .filter(p -> !coveredProductIds.contains(p.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();

            BigDecimal total = storeItems.stream()
                    .map(ItemPriceDetail::subtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean complete = missingProducts.isEmpty();

            results.add(new StoreOptimizationResult(
                    store.getId(),
                    store.getName(),
                    store.getCity(),
                    total,
                    storeItems,
                    complete,
                    missingProducts
            ));
        }

        // Complete stores first (sorted by price), then incomplete (sorted by fewest missing)
        results.sort(Comparator
                .comparing((StoreOptimizationResult r) -> !r.complete())
                .thenComparing(r -> r.complete()
                        ? r.totalPrice()
                        : BigDecimal.valueOf(r.missingProducts().size()))
        );

        return results;
    }
}
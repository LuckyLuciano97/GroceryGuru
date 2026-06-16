package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.ItemPriceDetail;
import org.example.groceryguru.dto.QuantityUpdateRequest;
import org.example.groceryguru.dto.ShoppingListItemRequest;
import org.example.groceryguru.dto.ShoppingListRequest;
import org.example.groceryguru.dto.StoreOptimizationResult;
import org.example.groceryguru.model.ShoppingList;
import org.example.groceryguru.model.ShoppingListItem;
import org.example.groceryguru.model.ShoppingListMember;
import org.example.groceryguru.service.ShoppingListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shopping-lists")
public class ShoppingListController {

    private final ShoppingListService shoppingListService;

    public ShoppingListController(ShoppingListService shoppingListService) {
        this.shoppingListService = shoppingListService;
    }

    // Access checks resolve the user from the JWT principal, never from
    // client-supplied ids. Admins bypass them.

    private Long authUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return shoppingListService.userIdForEmail(String.valueOf(auth.getPrincipal()));
    }

    private boolean isAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** Owner or shared member may act on the list. */
    private Long assertMemberAccess(Long listId) {
        Long userId = authUserId();
        if (!isAdmin() && !shoppingListService.canAccessList(listId, userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "No access to this list");
        }
        return userId;
    }

    /** Only the owner may act (delete, share). */
    private Long assertOwnerAccess(Long listId) {
        Long userId = authUserId();
        if (!isAdmin() && !shoppingListService.isOwner(listId, userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Only the list owner can do this");
        }
        return userId;
    }

    @GetMapping("/user/{userId}")
    public List<ShoppingList> getListsForUser(@PathVariable Long userId) {
        if (!isAdmin() && !authUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "You can only view your own lists");
        }
        return shoppingListService.getListsForUser(userId);
    }

    @PostMapping
    public ResponseEntity<ShoppingList> createList(@Valid @RequestBody ShoppingListRequest request) {
        ShoppingList list = shoppingListService.createList(request.name(), authUserId());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/{listId}/items")
    public ResponseEntity<ShoppingListItem> addItem(
            @PathVariable Long listId,
            @Valid @RequestBody ShoppingListItemRequest request) {
        assertMemberAccess(listId);
        ShoppingListItem item = shoppingListService.addItem(listId, request.productId(), request.quantity());
        return ResponseEntity.ok(item);
    }

    @PostMapping("/{listId}/generic-items")
    public ResponseEntity<ShoppingListItem> addGenericItem(
            @PathVariable Long listId,
            @RequestBody Map<String, Object> body) {
        assertMemberAccess(listId);
        String searchTerm = (String) body.get("searchTerm");
        Integer quantity = body.get("quantity") != null ? (Integer) body.get("quantity") : 1;
        ShoppingListItem item = shoppingListService.addGenericItem(listId, searchTerm, quantity);
        return ResponseEntity.ok(item);
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<ShoppingListItem> updateItemQuantity(
            @PathVariable Long itemId,
            @Valid @RequestBody QuantityUpdateRequest request) {
        assertMemberAccess(shoppingListService.getListIdForItem(itemId));
        ShoppingListItem item = shoppingListService.updateItemQuantity(itemId, request.quantity());
        return ResponseEntity.ok(item);
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long itemId) {
        assertMemberAccess(shoppingListService.getListIdForItem(itemId));
        shoppingListService.removeItem(itemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{listId}")
    public ResponseEntity<Void> deleteList(@PathVariable Long listId) {
        assertOwnerAccess(listId);
        shoppingListService.deleteList(listId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{listId}/optimize")
    public List<StoreOptimizationResult> optimize(
            @PathVariable Long listId,
            @RequestParam(required = false) String city) {
        assertMemberAccess(listId);
        return shoppingListService.optimize(listId, city);
    }

    @GetMapping("/alternatives")
    public List<ItemPriceDetail> getAlternatives(
            @RequestParam String chain,
            @RequestParam String searchTerm,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String city) {
        return shoppingListService.getAlternatives(chain, searchTerm, productId, city);
    }

    // --- Sharing ---
    @PostMapping("/{listId}/share")
    public ResponseEntity<?> shareList(
            @PathVariable Long listId,
            @RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        // requester comes from the token; the body's userId is ignored
        Long requestingUserId = authUserId();
        try {
            ShoppingListMember member = shoppingListService.shareList(listId, email, requestingUserId);
            return ResponseEntity.ok(member);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{listId}/members")
    public List<ShoppingListMember> getMembers(@PathVariable Long listId) {
        assertMemberAccess(listId);
        return shoppingListService.getMembers(listId);
    }

    @DeleteMapping("/{listId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long listId,
            @PathVariable Long memberId,
            @RequestParam(required = false) Long userId) {
        shoppingListService.removeMember(listId, memberId, authUserId());
        return ResponseEntity.noContent().build();
    }
}
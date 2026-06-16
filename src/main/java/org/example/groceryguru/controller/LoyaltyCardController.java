package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.LoyaltyCardRequest;
import org.example.groceryguru.model.LoyaltyCard;
import org.example.groceryguru.model.User;
import org.example.groceryguru.repository.LoyaltyCardRepo;
import org.example.groceryguru.repository.UserRepo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/loyalty-cards")
public class LoyaltyCardController {

    private final LoyaltyCardRepo cardRepo;
    private final UserRepo userRepo;

    public LoyaltyCardController(LoyaltyCardRepo cardRepo, UserRepo userRepo) {
        this.cardRepo = cardRepo;
        this.userRepo = userRepo;
    }

    // Resolve the user from the JWT principal (email), never from client input.
    private User authUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepo.findByEmail(String.valueOf(auth.getPrincipal()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    @GetMapping
    public List<LoyaltyCard> myCards() {
        return cardRepo.findByUserIdOrderByCreatedAtDesc(authUser().getId());
    }

    @PostMapping
    public ResponseEntity<LoyaltyCard> add(@Valid @RequestBody LoyaltyCardRequest req) {
        LoyaltyCard card = new LoyaltyCard();
        card.setUser(authUser());
        card.setChain(req.chain().trim());
        card.setNumber(req.number().trim());
        card.setCodeType(req.codeType() == null || req.codeType().isBlank()
                ? "BARCODE" : req.codeType().trim().toUpperCase());
        return ResponseEntity.status(HttpStatus.CREATED).body(cardRepo.save(card));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        LoyaltyCard card = cardRepo.findByIdAndUserId(id, authUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        cardRepo.delete(card);
    }
}

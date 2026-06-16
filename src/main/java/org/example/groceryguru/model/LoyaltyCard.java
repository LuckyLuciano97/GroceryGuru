package org.example.groceryguru.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loyalty_cards")
public class LoyaltyCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Display name of the chain ("Konzum", "Lidl"...) - drives the logo on the client
    @Column(nullable = false, length = 60)
    private String chain;

    // The number encoded on the physical card
    @Column(nullable = false, length = 64)
    private String number;

    // "BARCODE" or "QRCODE" - how the value should be rendered at checkout
    @Column(nullable = false, length = 16)
    private String codeType = "BARCODE";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}

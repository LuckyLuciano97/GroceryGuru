package org.example.groceryguru.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Data
@Entity
@Table(name = "prices",
uniqueConstraints = {
        @UniqueConstraint(
                name = "price_product_store_current",
                columnNames = {"product_id", "store_id", "is_current"}

        )
})
public class Price {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private BigDecimal price;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;



}
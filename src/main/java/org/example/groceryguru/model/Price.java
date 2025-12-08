package org.example.groceryguru.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;

@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prices",
uniqueConstraints = {
        @UniqueConstraint(
                name = "price_product_store_current",
                columnNames = {"product_id", "store_di", "is_current"}

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

    private Timestamp timestamp;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;



}
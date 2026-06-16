package org.example.groceryguru.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 150, nullable = false)
    private String name;

    /**
     * Cleaned, title-cased, brand-first version of {@link #name} for UI display.
     * The raw {@code name} is preserved for pg_trgm matching against the
     * Cjenoteka cache, so we don't break image lookups. UI should prefer
     * {@code displayName} when non-null and fall back to {@code name}.
     */
    @Column(length = 200)
    private String displayName;

    @Column(length = 600)
    private String description;

    @Column(length = 50, unique = true)
    private String barcode;

    @Column(length = 100)
    private String brand;

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String subcategory;

    @Column(length = 50)
    private String netQuantity;

    @Column(length = 20)
    private String unit;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean imageSearched = false;

    @JsonIgnore
    @OneToMany(mappedBy = "product")
    private List<Price> prices = new ArrayList<>();



}

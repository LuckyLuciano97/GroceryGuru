package org.example.groceryguru.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "storeChains")
public class StoreChain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column
    private String websiteUrl;

    @Column
    private String countryCode;


}

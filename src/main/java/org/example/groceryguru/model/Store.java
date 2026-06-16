package org.example.groceryguru.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Table(name = "stores")
@Entity
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "chain_id")
    private StoreChain chain;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50, unique = true)
    private String storeCode;

    @JsonIgnore
    @OneToMany(mappedBy = "store")
    private List<Price> prices = new ArrayList<>();

    @Column
    private String street;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column
    private String city;

    @Column
    private int postalCode;

    @Column
    private LocalTime opensAt;

    @Column
    private LocalTime closesAt;

}

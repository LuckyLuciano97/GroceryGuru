package org.example.groceryguru.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stores")
@Entity
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "chain_id")
    @Column(nullable = false, unique = true, length = 100)
    private StoreChain chain;

    @Column(nullable = false, length = 100)
    private String name;


    @OneToMany(mappedBy = "store")
    private List<Price> prices = new ArrayList<>();

    @Column
    private String street;


    @Column
    private String city;

    @Column
    private int postalCode;

    @Column
    private LocalTime opensAt;

    @Column
    private LocalTime closesAt;

}

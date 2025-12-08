package org.example.groceryguru.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Date;

@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100, nullable = false, unique = true)
    private String firstName;

    @Column(length = 80, nullable = false, unique = true)
    private String lastName;

    @Column(length = 80, nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(length = 100, nullable = false, unique = false)
    private String password;

    @Column
    private LocalDate birthDate;


    public User(String firstName, String lastName, String email, String password, LocalDate birthDate) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.password = password;
        this.birthDate = birthDate;
    }
}


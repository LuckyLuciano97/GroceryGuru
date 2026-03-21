package org.example.groceryguru.dto;

import java.time.LocalDate;

public record UserResponseDto(
        Long id,
        String firstName,
        String lastName,
        String email,
        LocalDate birthDate
) {}

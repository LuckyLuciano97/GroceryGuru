package org.example.groceryguru.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoyaltyCardRequest(
        @NotBlank @Size(max = 60) String chain,
        @NotBlank @Size(max = 64) String number,
        @Size(max = 16) String codeType
) {}

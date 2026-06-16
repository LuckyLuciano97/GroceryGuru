package org.example.groceryguru.dto;

/**
 * Returned after successful login/register.
 * Contains the JWT token and user info so the frontend has everything it needs.
 */
public record AuthResponse(
        String token,
        UserResponseDto user
) {}

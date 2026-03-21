package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.UserRequestDto;
import org.example.groceryguru.dto.UserResponseDto;
import org.example.groceryguru.model.User;
import org.example.groceryguru.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponseDto> getAll() {
        return userService.findAll()
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/{id}")
    public UserResponseDto getById(@PathVariable Long id) {
        return mapToDto(userService.findById(id));
    }

    @GetMapping("/by-email")
    public UserResponseDto getByEmail(@RequestParam String email) {
        return userService.getUserByEmail(email)
                .map(this::mapToDto)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("User not found with email: " + email));
    }

    @PostMapping
    public ResponseEntity<UserResponseDto> create(@Valid @RequestBody UserRequestDto request) {
        User user = new User(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password(),
                request.birthDate()
        );
        User saved = userService.createUser(user);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public UserResponseDto update(@PathVariable Long id, @Valid @RequestBody UserRequestDto request) {
        User updated = new User(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password(),
                request.birthDate()
        );
        return mapToDto(userService.updateUser(id, updated));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        userService.deleteUser(id);
    }

    private UserResponseDto mapToDto(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getBirthDate()
        );
    }
}

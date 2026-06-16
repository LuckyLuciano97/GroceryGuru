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
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * IDOR guard: mutations on /api/users/{id} are only allowed on your own
     * account, unless you're ADMIN. The JWT filter sets the principal to the
     * authenticated email.
     */
    private void assertSelfOrAdmin(Long targetUserId) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) return;

        String email = String.valueOf(auth.getPrincipal());
        boolean self = userService.getUserByEmail(email)
                .map(u -> u.getId().equals(targetUserId))
                .orElse(false);
        if (!self) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "You can only modify your own account");
        }
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
        assertSelfOrAdmin(id);
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
        assertSelfOrAdmin(id);
        userService.deleteUser(id);
    }

    @PutMapping("/{id}/profile")
    public UserResponseDto updateProfile(@PathVariable Long id, @RequestBody Map<String, String> body) {
        assertSelfOrAdmin(id);
        User user = userService.findById(id);
        if (body.containsKey("firstName")) user.setFirstName(body.get("firstName"));
        if (body.containsKey("lastName")) user.setLastName(body.get("lastName"));
        if (body.containsKey("city")) user.setCity(body.get("city"));
        User saved = userService.createUser(user);
        return mapToDto(saved);
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        assertSelfOrAdmin(id);
        userService.changePassword(id, body.get("currentPassword"), body.get("newPassword"));
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/city")
    public UserResponseDto updateCity(@PathVariable Long id, @RequestBody Map<String, String> body) {
        assertSelfOrAdmin(id);
        String city = body.get("city");
        User user = userService.findById(id);
        user.setCity(city);
        User saved = userService.createUser(user);
        return mapToDto(saved);
    }

    private UserResponseDto mapToDto(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getBirthDate(),
                user.getRole(),
                user.getCity()
        );
    }
}

package com.luckfox.imagebuilder.controller;

import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.dto.UserRequest;
import com.luckfox.imagebuilder.dto.UserResponse;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.UserRepository;
import com.luckfox.imagebuilder.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return userRepository.findByEmail(currentEmail())
                .map(user -> ResponseEntity.ok(UserResponse.from(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/me/images")
    @Transactional(readOnly = true)
    public List<ImageDto> getUserImages() {
        return userRepository.findByEmail(currentEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден"))
                .getOrderedImages().stream()
                .map(ImageDto::from)
                .collect(Collectors.toList());
    }

    // --- Управление пользователями (ADMIN) ---

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> getAll(@RequestParam(required = false) String search,
                                             @RequestParam(required = false) String role,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return userService.search(search, role, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getById(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.update(id, request, currentEmail()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id, currentEmail());
        return ResponseEntity.noContent().build();
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

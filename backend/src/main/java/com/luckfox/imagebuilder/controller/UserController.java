package com.luckfox.imagebuilder.controller;

import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.dto.UserResponse;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        String email = currentEmail();
        return userRepository.findByEmail(email)
                .map(user -> ResponseEntity.ok(UserResponse.builder()
                        .name(user.getName())
                        .surname(user.getSurname())
                        .email(user.getEmail())
                        .roles(user.getRoles().stream()
                                .map(Role::getRoleName)
                                .collect(Collectors.toSet()))
                        .build()))
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

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}

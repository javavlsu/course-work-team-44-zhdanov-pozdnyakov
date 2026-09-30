package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.UserRequest;
import com.luckfox.imagebuilder.dto.UserResponse;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.RoleRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static com.luckfox.imagebuilder.TestData.role;
import static com.luckfox.imagebuilder.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @InjectMocks
    private UserService userService;

    private final Role userRole = role(1, "USER");
    private final Role adminRole = role(2, "ADMIN");

    @BeforeEach
    void setUp() {
        lenient().when(roleRepository.findByRoleName("USER")).thenReturn(Optional.of(userRole));
        lenient().when(roleRepository.findByRoleName("ADMIN")).thenReturn(Optional.of(adminRole));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserRequest request(String email, String password, String... roles) {
        return UserRequest.builder().name("Иван").surname("Петров").email(email).password(password)
                .roles(Set.of(roles)).build();
    }

    @Test
    void createEncodesPasswordTrimsEmailAndAssignsRoles() {
        when(passwordEncoder.encode("Secret@123")).thenReturn("bcrypt-hash");

        UserResponse res = userService.create(request("  ivan@test.io ", "Secret@123", "USER", "ADMIN"));

        assertThat(res.getEmail()).isEqualTo("ivan@test.io");
        assertThat(res.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> "bcrypt-hash".equals(u.getPassword())));
    }

    @Test
    void createWithoutPasswordIsRejected() {
        assertThatThrownBy(() -> userService.create(request("ivan@test.io", null, "USER")))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void duplicateEmailIsRejected() {
        when(userRepository.findByEmail("taken@test.io")).thenReturn(Optional.of(user(5, "taken@test.io")));

        assertThatThrownBy(() -> userService.create(request("taken@test.io", "Secret@123", "USER")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    void unknownRoleIsRejected() {
        when(roleRepository.findByRoleName("ROOT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request("ivan@test.io", "Secret@123", "ROOT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ROOT");
    }

    @Test
    void updateKeepsOwnEmailAndOldPasswordWhenNotGiven() {
        User ivan = user(5, "ivan@test.io", userRole);
        when(userRepository.findById(5L)).thenReturn(Optional.of(ivan));
        when(userRepository.findByEmail("ivan@test.io")).thenReturn(Optional.of(ivan));

        userService.update(5L, request("ivan@test.io", null, "USER"), "admin@test.io");

        assertThat(ivan.getPassword()).isEqualTo("hash");
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void adminCannotRemoveOwnAdminRole() {
        User admin = user(1, "admin@test.io", adminRole);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.update(1L, request("admin@test.io", null, "USER"), "admin@test.io"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void adminCannotDeleteThemselves() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1, "admin@test.io", adminRole)));

        assertThatThrownBy(() -> userService.delete(1L, "admin@test.io")).isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteOfAnotherUserSucceeds() {
        User ivan = user(5, "ivan@test.io", userRole);
        when(userRepository.findById(5L)).thenReturn(Optional.of(ivan));

        userService.delete(5L, "admin@test.io");

        verify(userRepository).delete(ivan);
    }

    @Test
    void getOfMissingUserFails() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(404L)).isInstanceOf(ResourceNotFoundException.class);
    }
}

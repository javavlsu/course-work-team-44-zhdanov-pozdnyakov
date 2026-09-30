package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.AuthenticationRequest;
import com.luckfox.imagebuilder.dto.AuthenticationResponse;
import com.luckfox.imagebuilder.dto.RegisterRequest;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.TooManyRequestsException;
import com.luckfox.imagebuilder.repository.RoleRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static com.luckfox.imagebuilder.TestData.role;
import static com.luckfox.imagebuilder.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private LoginAttemptService loginAttemptService;
    @InjectMocks
    private AuthenticationService service;

    private static final String IP = "10.0.0.1";

    @Test
    void registerCreatesUserWithEncodedPasswordAndUserRole() {
        Role userRole = role(1, "USER");
        when(userRepository.findByEmail("new@test.io")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Secret@123")).thenReturn("bcrypt-hash");
        when(roleRepository.findByRoleName("USER")).thenReturn(Optional.of(userRole));
        when(jwtService.generateToken(any())).thenReturn("jwt");

        AuthenticationResponse res = service.register(
                RegisterRequest.builder().name("Иван").email("new@test.io").password("Secret@123").build());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getValue().getRoles()).containsExactly(userRole);
        assertThat(res.getToken()).isEqualTo("jwt");
    }

    @Test
    void registerCreatesMissingUserRole() {
        when(userRepository.findByEmail("new@test.io")).thenReturn(Optional.empty());
        when(roleRepository.findByRoleName("USER")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(RegisterRequest.builder().name("Иван").email("new@test.io").password("Secret@123").build());

        verify(roleRepository).save(org.mockito.ArgumentMatchers.argThat(r -> "USER".equals(r.getRoleName())));
    }

    @Test
    void registerWithTakenEmailIsRejected() {
        when(userRepository.findByEmail("taken@test.io")).thenReturn(Optional.of(user(1, "taken@test.io")));

        assertThatThrownBy(() -> service.register(
                RegisterRequest.builder().name("Иван").email("taken@test.io").password("Secret@123").build()))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void successfulLoginReturnsTokenAndResetsCounter() {
        User alice = user(1, "alice@test.io");
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));
        when(jwtService.generateToken(alice)).thenReturn("jwt");

        AuthenticationResponse res = service.authenticate(new AuthenticationRequest("alice@test.io", "Secret@123"), IP);

        assertThat(res.getToken()).isEqualTo("jwt");
        verify(loginAttemptService).checkAllowed(IP, "alice@test.io");
        verify(loginAttemptService).onSuccess("alice@test.io");
    }

    @Test
    void failedLoginIsCountedAndRethrown() {
        doThrow(new BadCredentialsException("bad")).when(authenticationManager).authenticate(any());

        assertThatThrownBy(() -> service.authenticate(new AuthenticationRequest("alice@test.io", "wrong"), IP))
                .isInstanceOf(BadCredentialsException.class);
        verify(loginAttemptService).onFailure(IP, "alice@test.io");
        verify(loginAttemptService, never()).onSuccess(any());
    }

    @Test
    void blockedLoginSkipsPasswordCheck() {
        doThrow(new TooManyRequestsException("blocked", 60)).when(loginAttemptService).checkAllowed(IP, "alice@test.io");

        assertThatThrownBy(() -> service.authenticate(new AuthenticationRequest("alice@test.io", "any"), IP))
                .isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(authenticationManager);
    }
}

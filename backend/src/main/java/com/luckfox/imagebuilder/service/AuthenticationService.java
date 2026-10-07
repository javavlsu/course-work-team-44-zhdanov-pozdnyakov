package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.AuthenticationRequest;
import com.luckfox.imagebuilder.dto.AuthenticationResponse;
import com.luckfox.imagebuilder.dto.RegisterRequest;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.repository.RoleRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttemptService;

    public AuthenticationResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BadRequestException("Пользователь с таким email уже существует");
        }
        var user = new User();
        user.setName(request.getName());
        user.setSurname(request.getSurname());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        Role userRole = roleRepository.findByRoleName("USER")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("USER");
                    return roleRepository.save(r);
                });
        Set<Role> roles = new HashSet<>();
        roles.add(userRole);
        user.setRoles(roles);

        userRepository.save(user);
        var jwtToken = jwtService.generateToken(user);
        return AuthenticationResponse.builder()
                .token(jwtToken)
                .build();
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request, String clientIp) {
        loginAttemptService.checkAllowed(clientIp, request.getEmail());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );
        } catch (AuthenticationException e) {
            loginAttemptService.onFailure(clientIp, request.getEmail());
            throw e;
        }
        loginAttemptService.onSuccess(request.getEmail());
        var user = userRepository.findByEmail(request.getEmail())
                .orElseThrow();
        var jwtToken = jwtService.generateToken(user);
        return AuthenticationResponse.builder()
                .token(jwtToken)
                .build();
    }
}

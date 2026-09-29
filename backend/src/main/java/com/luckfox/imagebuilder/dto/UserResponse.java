package com.luckfox.imagebuilder.dto;

import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {
    private Long id;
    private String name;
    private String surname;
    private String email;
    private Set<String> roles;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .surname(user.getSurname())
                .email(user.getEmail())
                .roles(user.getRoles() == null ? Set.of()
                        : user.getRoles().stream().map(Role::getRoleName).collect(Collectors.toSet()))
                .build();
    }
}

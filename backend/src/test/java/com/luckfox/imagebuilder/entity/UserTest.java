package com.luckfox.imagebuilder.entity;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static com.luckfox.imagebuilder.TestData.role;
import static com.luckfox.imagebuilder.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void authoritiesArePrefixedRoles() {
        User u = user(1, "admin@test.io", role(1, "USER"), role(2, "ADMIN"));

        assertThat(u.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void usernameIsEmailAndAccountIsActive() {
        User u = user(1, "admin@test.io");

        assertThat(u.getUsername()).isEqualTo("admin@test.io");
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.isAccountNonLocked()).isTrue();
        assertThat(u.isAccountNonExpired()).isTrue();
        assertThat(u.isCredentialsNonExpired()).isTrue();
    }
}

package com.luckfox.imagebuilder.service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService();
    private final UserDetails alice = User.withUsername("alice@test.io").password("x").authorities(List.of()).build();
    private final UserDetails bob = User.withUsername("bob@test.io").password("x").authorities(List.of()).build();

    @Test
    void generatedTokenCarriesSubjectAndIsValidForOwner() {
        String token = jwtService.generateToken(alice);

        assertThat(jwtService.extractUsername(token)).isEqualTo("alice@test.io");
        assertThat(jwtService.isTokenValid(token, alice)).isTrue();
    }

    @Test
    void tokenIsNotValidForAnotherUser() {
        String token = jwtService.generateToken(alice);

        assertThat(jwtService.isTokenValid(token, bob)).isFalse();
    }

    @Test
    void extraClaimsArePreserved() {
        String token = jwtService.generateToken(Map.of("scope", "build"), alice);

        String scope = jwtService.extractClaim(token, c -> c.get("scope", String.class));

        assertThat(scope).isEqualTo("build");
    }

    @Test
    void tokenLivesLongerThanItWasIssued() {
        String token = jwtService.generateToken(alice);

        Date issued = jwtService.extractClaim(token, c -> c.getIssuedAt());
        Date expires = jwtService.extractClaim(token, c -> c.getExpiration());
        assertThat(expires).isAfter(issued);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.generateToken(alice);
        String forged = Jwts.builder().setSubject("admin@luckfox.io")
                .signWith(Keys.secretKeyFor(SignatureAlgorithm.HS256)).compact();
        String mixed = forged.substring(0, forged.lastIndexOf('.')) + token.substring(token.lastIndexOf('.'));

        assertThatThrownBy(() -> jwtService.extractUsername(mixed)).isInstanceOf(SignatureException.class);
        assertThatThrownBy(() -> jwtService.extractUsername(forged)).isInstanceOf(SignatureException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = Jwts.builder().setSubject("alice@test.io")
                .setExpiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(serviceKey(), SignatureAlgorithm.HS256).compact();

        assertThatThrownBy(() -> jwtService.isTokenValid(expired, alice)).isInstanceOf(ExpiredJwtException.class);
    }

    /** Ключ сервиса закрыт — восстанавливаем его так же, как сервис (Base64-секрет → HMAC-ключ). */
    private static java.security.Key serviceKey() {
        try {
            var field = JwtService.class.getDeclaredField("SECRET_KEY");
            field.setAccessible(true);
            byte[] bytes = io.jsonwebtoken.io.Decoders.BASE64.decode((String) field.get(null));
            return Keys.hmacShaKeyFor(bytes);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

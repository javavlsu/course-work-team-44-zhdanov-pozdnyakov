package com.luckfox.imagebuilder.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static <T> Set<String> invalidFields(T bean) {
        return validator.validate(bean).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    private static RegisterRequest register(String email, String password) {
        return RegisterRequest.builder().name("Иван").email(email).password(password).build();
    }

    @Test
    void validRegistrationPasses() {
        assertThat(invalidFields(register("ivan@test.io", "Secret@123"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sh@1rt", "nodigits@Aa", "NOLOWER@123", "noupper@123", "NoSpecial123"})
    void weakPasswordsAreRejected(String password) {
        assertThat(invalidFields(register("ivan@test.io", password))).containsExactly("password");
    }

    @Test
    void malformedEmailAndBlankNameAreRejected() {
        RegisterRequest req = RegisterRequest.builder().name(" ").email("not-an-email").password("Secret@123").build();

        assertThat(invalidFields(req)).containsExactlyInAnyOrder("name", "email");
    }

    @Test
    void buildRequestNeedsNameDistroAndComponents() {
        assertThat(invalidFields(new BuildRequest())).containsExactlyInAnyOrder("name", "distro", "componentIds");
        assertThat(invalidFields(BuildRequest.builder().name("x").distro("buildroot").componentIds(List.of()).build()))
                .containsExactly("componentIds");
    }

    @Test
    void componentCostCannotBeNegative() {
        ComponentRequest req = ComponentRequest.builder().name("x").typeId(1L).cost(-1L).build();

        assertThat(invalidFields(req)).containsExactly("cost");
    }

    @Test
    void userRequestAllowsMissingPasswordButNeedsRole() {
        UserRequest req = UserRequest.builder().name("Иван").email("ivan@test.io").roles(Set.of()).build();

        assertThat(invalidFields(req)).containsExactly("roles");
    }
}

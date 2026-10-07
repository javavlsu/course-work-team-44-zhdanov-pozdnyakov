package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptServiceTest {

    private static final String IP = "10.0.0.1";

    private final LoginAttemptService service = new LoginAttemptService(3, 5, 300);

    @Test
    void allowsAttemptsBelowEmailLimit() {
        service.onFailure(IP, "a@test.io");
        service.onFailure(IP, "a@test.io");

        assertThatCode(() -> service.checkAllowed(IP, "a@test.io")).doesNotThrowAnyException();
    }

    @Test
    void blocksEmailAfterLimitWithRetryAfterWithinWindow() {
        for (int i = 0; i < 3; i++) {
            service.onFailure(IP, "a@test.io");
        }

        assertThatThrownBy(() -> service.checkAllowed("10.0.0.2", "a@test.io"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 300L));
    }

    @Test
    void emailKeyIgnoresCaseAndSpaces() {
        for (int i = 0; i < 3; i++) {
            service.onFailure(IP, " A@Test.IO ");
        }

        assertThatThrownBy(() -> service.checkAllowed("10.0.0.2", "a@test.io"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void blocksAddressAfterFailuresOnDifferentEmails() {
        for (int i = 0; i < 5; i++) {
            service.onFailure(IP, "user" + i + "@test.io");
        }

        assertThatThrownBy(() -> service.checkAllowed(IP, "fresh@test.io"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> service.checkAllowed("10.0.0.2", "fresh@test.io")).doesNotThrowAnyException();
    }

    @Test
    void successResetsEmailCounter() {
        service.onFailure(IP, "a@test.io");
        service.onFailure(IP, "a@test.io");
        service.onSuccess("a@test.io");
        service.onFailure(IP, "a@test.io");
        service.onFailure(IP, "a@test.io");

        assertThatCode(() -> service.checkAllowed("10.0.0.2", "a@test.io")).doesNotThrowAnyException();
    }

    @Test
    void counterExpiresAfterWindow() throws InterruptedException {
        LoginAttemptService shortWindow = new LoginAttemptService(1, 10, 1);
        shortWindow.onFailure(IP, "a@test.io");
        assertThatThrownBy(() -> shortWindow.checkAllowed(IP, "a@test.io"))
                .isInstanceOf(TooManyRequestsException.class);

        Thread.sleep(1100);

        assertThatCode(() -> shortWindow.checkAllowed(IP, "a@test.io")).doesNotThrowAnyException();
    }

    @Test
    void nullEmailDoesNotFail() {
        assertThatCode(() -> {
            service.onFailure(IP, null);
            service.checkAllowed(IP, null);
        }).doesNotThrowAnyException();
    }
}

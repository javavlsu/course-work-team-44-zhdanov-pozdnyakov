package com.luckfox.imagebuilder.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationErrorsAreReturnedPerField() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "req");
        result.addError(new FieldError("req", "email", "Некорректный email"));
        MethodParameter param = new MethodParameter(Object.class.getMethod("toString"), -1);

        ResponseEntity<Map<String, String>> res =
                handler.handleValidationExceptions(new MethodArgumentNotValidException(param, result));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("email", "Некорректный email");
    }

    @Test
    void notFoundIs404WithMessage() {
        ResponseEntity<Map<String, String>> res = handler.handleNotFound(new ResourceNotFoundException("нет"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).containsEntry("error", "нет");
    }

    @Test
    void tooManyRequestsIs429WithRetryAfter() {
        ResponseEntity<Map<String, String>> res =
                handler.handleTooManyRequests(new TooManyRequestsException("подождите", 42));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(res.getBody()).containsEntry("error", "подождите");
    }

    @Test
    void exhaustedConnectionPoolIs503() {
        ResponseEntity<Map<String, String>> res =
                handler.handleNoConnection(new CannotCreateTransactionException("pool timeout"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res.getBody().get("error")).doesNotContain("pool timeout");
    }

    @Test
    void optimisticLockConflictIs409() {
        ResponseEntity<Map<String, String>> res =
                handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException(Object.class, 1L));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}

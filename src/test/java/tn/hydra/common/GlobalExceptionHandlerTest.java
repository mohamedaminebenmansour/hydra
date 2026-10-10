package tn.hydra.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessRuleErrorsReturn409WithCodeAndMessage() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res = handler.businessRule(
                new BusinessRuleException("TOXIC_PAIR",
                        "The person who buys cannot be the person who pays."));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("TOXIC_PAIR");
        assertThat(res.getBody().message())
                .isEqualTo("The person who buys cannot be the person who pays.");
    }

    @Test
    void accessDeniedReturns403InsteadOf500() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.accessDenied(new AccessDeniedException("denied"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void malformedRequestBodyReturns400() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.badRequest(new Exception("unreadable"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void anythingElseReturns500WithFrenchFallback() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.generic(new IllegalStateException("boom"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().code()).isEqualTo("INTERNAL");
        assertThat(res.getBody().message()).isEqualTo("Une erreur interne est survenue");
    }
}

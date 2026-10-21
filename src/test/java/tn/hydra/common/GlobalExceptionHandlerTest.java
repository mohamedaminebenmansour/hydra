package tn.hydra.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.NoSuchElementException;
import jakarta.validation.ConstraintViolationException;
import tn.hydra.document.domain.DocType;

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
                handler.malformed(new Exception("unreadable"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void anythingElseReturns500WithClassNameInMessage() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.generic(new IllegalStateException("boom"), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().code()).isEqualTo("INTERNAL");
        assertThat(res.getBody().message()).startsWith("INTERNAL: IllegalStateException");
    }

    @Test
    void missingMultipartPartReturns400() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.missingPart(new MissingServletRequestPartException("file"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("MISSING_PART");
        assertThat(res.getBody().message()).contains("file");
    }

    @Test
    void missingRequiredParameterReturns400() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.missingParameter(new MissingServletRequestParameterException("title", "String"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("MISSING_PARAMETER");
        assertThat(res.getBody().message()).contains("title");
    }

    @Test
    void typeMismatchReturns400() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", DocType.class, "docType", null, null);
        ResponseEntity<GlobalExceptionHandler.ApiError> res = handler.typeMismatch(e);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("INVALID_PARAMETER");
        assertThat(res.getBody().message()).contains("docType");
    }

    @Test
    void noSuchElementReturns404() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.noSuchElement(new NoSuchElementException("Utilisateur introuvable"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().message()).isEqualTo("Utilisateur introuvable");
    }

    @Test
    void illegalArgumentReturns400() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.illegalArgument(new IllegalArgumentException("mauvaise valeur"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(res.getBody().message()).isEqualTo("mauvaise valeur");
    }

    @Test
    void constraintViolationReturns400() {
        ResponseEntity<GlobalExceptionHandler.ApiError> res =
                handler.constraintViolation(new ConstraintViolationException("champ invalide", java.util.Set.of()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_FAILED");
    }
}

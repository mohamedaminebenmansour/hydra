package tn.hydra.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.FileStorageException;
import tn.hydra.exception.ResourceNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message) {}

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> businessRule(BusinessRuleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ApiError> fileStorage(FileStorageException e) {
        HttpStatus status = "FILE_NOT_FOUND".equals(e.getCode())
                ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("FILE_TOO_LARGE", "Fichier trop volumineux"));
    }

    // Method security denies must stay 403; without this the generic handler below
    // would turn them into 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", "Vous n'avez pas cette permission"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.warn("Data integrity violation: {}", msg);
        // Common case: unique constraint on (document_id, rev_label) or FK violation
        if (msg != null && (msg.contains("unique") || msg.contains("duplicate") || msg.contains("rev_label"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("REV_EXISTS", "Cette révision existe déjà"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("DATA_INTEGRITY", "Violation de contrainte de données"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> malformed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", "Requête invalide"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL", "Une erreur interne est survenue"));
    }
}
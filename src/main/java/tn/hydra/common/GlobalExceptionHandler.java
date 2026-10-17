package tn.hydra.common;

import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.NoSuchElementException;
import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    @ExceptionHandler(org.hibernate.LazyInitializationException.class)
    public ResponseEntity<ApiError> lazyInitialization(LazyInitializationException e,
                                                       HttpServletRequest request) {
        log.error("LazyInitializationException on {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.getClass().getName(), e);
        String message = "Impossible de lire une association chargée en différé : la session JPA est fermée "
                + "(spring.jpa.open-in-view=false) — " + e.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("LAZY_INIT", message));
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ApiError> fileStorage(FileStorageException e, HttpServletRequest request) {
        log.error("FileStorage failure on {} {} (code={}): {}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage(), e);
        String causeMsg = "";
        Throwable cause = e.getCause();
        if (cause != null) {
            String m = cause.getMessage();
            causeMsg = " | cause: " + (m != null ? truncate(m) : cause.getClass().getSimpleName());
        }
        HttpStatus status = "FILE_NOT_FOUND".equals(e.getCode())
                ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(e.getCode(), e.getMessage() + causeMsg));
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

    /**
     * Catches Hibernate/JPA persistence failures that escape the @Transactional boundary.
     * Common causes on Supabase / Vercel Postgres:
     *   - Row Level Security enabled with no policies → INSERT/SELECT denied
     *   - Missing sequence (doc_reference_seq) → relation does not exist
     *   - FK violation on document.folder_id or document.created_by
     */
    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<ApiError> persistence(PersistenceException e, HttpServletRequest request) {
        Throwable root = rootCause(e);
        log.error("[DB] PersistenceException on {} {} — root: {} : {}",
                request.getMethod(), request.getRequestURI(),
                root.getClass().getSimpleName(), root.getMessage(), e);
        String detail = root.getMessage() != null ? truncate(root.getMessage()) : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new ApiError("DB_ERROR",
                        "Erreur base de données : " + detail));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> malformed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", "Requête invalide"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("MISSING_PART",
                        "Champ de fichier manquant dans la requête multipart : " + e.getRequestPartName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(
            MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("MISSING_PARAMETER",
                        "Paramètre requis manquant : " + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName() != null ? e.getName() : "paramètre";
        String value = e.getValue() != null ? String.valueOf(e.getValue()) : "";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_PARAMETER",
                        "Valeur invalide pour le paramètre " + name + " : " + value));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> noSuchElement(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND",
                        e.getMessage() != null ? e.getMessage() : "Ressource introuvable"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST",
                        e.getMessage() != null ? e.getMessage() : "Argument invalide"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", "Violation de contrainte de validation"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e, HttpServletRequest request) {
        Throwable root = rootCause(e);
        log.error("Unhandled exception on {} {} — type={} rootType={} rootMsg={}",
                request.getMethod(), request.getRequestURI(),
                e.getClass().getSimpleName(),
                root.getClass().getSimpleName(),
                root.getMessage(), e);
        // Include class + root cause so the Network tab is actionable while diagnosing
        // production 500s without needing direct log access.
        String message = e.getClass().getSimpleName()
                + (e.getMessage() != null ? " — " + truncate(e.getMessage()) : "")
                + (root != e ? " | root: " + root.getClass().getSimpleName()
                    + (root.getMessage() != null ? " — " + truncate(root.getMessage()) : "") : "");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("INTERNAL", message));
    }

    /** Walk the cause chain to find the deepest root cause. */
    private static Throwable rootCause(Throwable t) {
        Throwable cause = t.getCause();
        return (cause == null || cause == t) ? t : rootCause(cause);
    }

    /** Truncate long cause messages so the JSON body stays readable. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "…[truncated]" : s;
    }
}
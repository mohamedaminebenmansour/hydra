package tn.hydra.exception;

/** Thrown on invalid client input that is not covered by bean validation. Maps to 400. */
public class BadRequestException extends RuntimeException {
    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
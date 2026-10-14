package tn.hydra.exception;

/** Thrown when the file storage backend fails (I/O, missing file, too large). Maps to 500/413. */
public class FileStorageException extends RuntimeException {
    private final String code;

    public FileStorageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FileStorageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}
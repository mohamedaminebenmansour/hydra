package tn.hydra.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tn.hydra.exception.FileStorageException;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Phase-1 local-disk storage. Keys are relative paths like
 * org/{org}/project/{proj}/{folder}/doc/{doc}/RevA__<file>. Files are only ever
 * served through the API download endpoint, never by direct path.
 *
 * <b>Production requirement:</b> {@code HYDRA_STORAGE_ROOT} MUST be set to a writable
 * persistent path (e.g. {@code /var/data/files} on Render backed by a Render Disk).
 * The default {@code ./data/files} is for local development only and will not survive
 * redeploys on Render. At startup, if the storage root cannot be created, the app fails
 * fast with {@code STORAGE_INIT_FAILED} rather than failing on the first upload.
 *
 * Active when {@code hydra.storage.type=local} (or when the property is missing).
 */
@ConditionalOnProperty(name = "hydra.storage.type", havingValue = "local", matchIfMissing = true)
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(@Value("${hydra.storage.root:${HYDRA_STORAGE_ROOT:./data/files}}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        log.info("LocalFileStorage root = {}", root);
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_INIT_FAILED",
                    "Failed to create storage directory at " + root + ". Set HYDRA_STORAGE_ROOT to a writable persistent path.", e);
        }
    }

    @Override
    public StoredFile save(String key, byte[] content, String contentType) {
        log.info("LocalFileStorage.save start root={} key={} bytes={} contentType={}",
                root, key, content.length, contentType);
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            log.info("LocalFileStorage.save OK key={} size={}", key, content.length);
            return new StoredFile(key, content.length, sha256(content));
        } catch (IOException e) {
            log.error("LocalFileStorage.save FAILED root={} key={}: {} — {}",
                    root, key, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new FileStorageException("STORAGE_WRITE_FAILED",
                    "Failed to store file: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_READ_FAILED", "File unreadable", e);
        }
    }

    @Override
    public Resource loadAsResource(String key) {
        try {
            Path path = resolve(key);
            if (!Files.exists(path)) {
                throw new FileStorageException("FILE_NOT_FOUND", "File not found");
            }
            return new UrlResource(path.toUri());
        } catch (Exception e) {
            if (e instanceof FileStorageException fse) throw fse;
            throw new FileStorageException("FILE_NOT_FOUND", "File not found", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_DELETE_FAILED", "Failed to delete file", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        // Path traversal protection: ensure target is under root
        if (!target.startsWith(root)) {
            throw new FileStorageException("INVALID_PATH", "Chemin de stockage invalide");
        }
        return target;
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new FileStorageException("CHECKSUM_FAILED", "Empreinte indisponible", e);
        }
    }
}
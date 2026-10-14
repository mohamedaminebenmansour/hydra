package tn.hydra.document.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tn.hydra.exception.FileStorageException;

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
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${hydra.storage.root:./data/files}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile save(String key, byte[] content, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return new StoredFile(key, content.length, sha256(content));
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_WRITE_FAILED", "Échec d'enregistrement du fichier", e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_READ_FAILED", "Fichier illisible", e);
        }
    }

    @Override
    public Resource loadAsResource(String key) {
        try {
            Path path = resolve(key);
            if (!Files.exists(path)) {
                throw new FileStorageException("FILE_NOT_FOUND", "Fichier introuvable");
            }
            return new UrlResource(path.toUri());
        } catch (Exception e) {
            if (e instanceof FileStorageException fse) throw fse;
            throw new FileStorageException("FILE_NOT_FOUND", "Fichier introuvable", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new FileStorageException("STORAGE_DELETE_FAILED", "Échec de suppression du fichier", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    private Path resolve(String key) {
        return root.resolve(key).normalize();
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
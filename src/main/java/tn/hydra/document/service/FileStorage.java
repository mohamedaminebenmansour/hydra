package tn.hydra.document.service;

import org.springframework.core.io.Resource;

/**
 * Pluggable binary storage. Phase-1 uses LocalFileStorage; a Supabase/S3
 * implementation can be swapped in later without touching the services.
 */
public interface FileStorage {

    StoredFile save(String key, byte[] content, String contentType);

    byte[] load(String key);

    Resource loadAsResource(String key);

    void delete(String key);

    boolean exists(String key);
}
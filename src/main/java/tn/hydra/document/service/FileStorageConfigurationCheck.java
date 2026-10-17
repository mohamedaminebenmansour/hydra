package tn.hydra.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup guard: exactly one {@link FileStorage} implementation must be active.
 * {@code LocalFileStorage} is active when {@code hydra.storage.type=local} (or missing);
 * {@code S3FileStorage} when {@code hydra.storage.type=s3}. If the property is set to any
 * other value, no implementation is registered and this component fails the app at startup
 * with a clear message instead of failing on the first upload.
 */
@Component
public class FileStorageConfigurationCheck {

    private static final Logger log = LoggerFactory.getLogger(FileStorageConfigurationCheck.class);

    public FileStorageConfigurationCheck(List<FileStorage> storages) {
        if (storages.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune implémentation FileStorage active. Définissez hydra.storage.type=local "
                            + "(par défaut) ou hydra.storage.type=s3 (avec hydra.s3.endpoint/region/bucket).");
        }
        if (storages.size() > 1) {
            throw new IllegalStateException(
                    "Plusieurs implémentations FileStorage actives (" + storages.size()
                            + ") : " + storages.stream()
                            .map(s -> s.getClass().getSimpleName()).toList()
                            + ". hydra.storage.type doit désigner une seule implémentation.");
        }
        log.info("FileStorage actif : {}", storages.get(0).getClass().getSimpleName());
    }
}
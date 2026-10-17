package tn.hydra.document.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tn.hydra.exception.FileStorageException;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@ConditionalOnProperty(name = "hydra.storage.type", havingValue = "s3")
@Component
public class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final S3Client s3;
    private final String endpoint;
    private final String region;
    private final String bucket;

    public S3FileStorage(
            @Value("${hydra.s3.endpoint:}") String endpoint,
            @Value("${hydra.s3.region:eu-west-1}") String region,
            @Value("${hydra.s3.bucket:documents}") String bucket) {
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        // Never log secrets — endpoint/region/bucket only.
        log.info("S3FileStorage constructor endpoint={} region={} bucket={}", endpoint, region, bucket);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(true);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.s3 = builder.build();
    }

    @PostConstruct
    void validate() {
        if (endpoint == null || endpoint.isBlank()) {
            throw new FileStorageException("S3_CONFIG_INVALID",
                    "hydra.storage.type=s3 mais hydra.s3.endpoint (HYDRA_S3_ENDPOINT) est vide. "
                            + "Exemple Supabase : https://<PROJECT_REF>.supabase.co/storage/v1/s3");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new FileStorageException("S3_CONFIG_INVALID",
                    "hydra.storage.type=s3 mais hydra.s3.bucket (HYDRA_S3_BUCKET) est vide.");
        }
        log.info("S3FileStorage ready endpoint={} region={} bucket={}", endpoint, region, bucket);
    }

    @Override
    public StoredFile save(String key, byte[] content, String contentType) {
        log.info("S3FileStorage.save start bucket={} key={} bytes={} contentType={}",
                bucket, key, content.length, contentType);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3.putObject(request, software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
            log.info("S3FileStorage.save OK bucket={} key={} size={}", bucket, key, content.length);
            return new StoredFile(key, content.length, sha256(content));
        } catch (SdkException e) {
            log.error("S3FileStorage.save FAILED bucket={} key={}: {}", bucket, key, e.getMessage(), e);
            throw new FileStorageException("STORAGE_WRITE_FAILED",
                    "Échec d'enregistrement du fichier: " + truncate(e.getMessage()), e);
        }
    }

    /** Truncate long SDK error messages so JSON body + logs stay readable. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 300 ? s.substring(0, 300) + "…[truncated]" : s;
    }

    @Override
    public byte[] load(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(request);
            return response.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new FileStorageException("FILE_NOT_FOUND", "Fichier introuvable", e);
        } catch (SdkException e) {
            log.error("S3FileStorage.load FAILED bucket={} key={}: {}", bucket, key, e.getMessage(), e);
            throw new FileStorageException("STORAGE_READ_FAILED",
                    "Fichier illisible: " + truncate(e.getMessage()), e);
        }
    }

    @Override
    public Resource loadAsResource(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            ResponseInputStream<GetObjectResponse> response = s3.getObject(request);
            return new InputStreamResource(response);
        } catch (NoSuchKeyException e) {
            throw new FileStorageException("FILE_NOT_FOUND", "Fichier introuvable", e);
        } catch (SdkException e) {
            log.error("S3FileStorage.loadAsResource FAILED bucket={} key={}: {}", bucket, key, e.getMessage(), e);
            throw new FileStorageException("STORAGE_READ_FAILED",
                    "Fichier illisible: " + truncate(e.getMessage()), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3.deleteObject(request);
        } catch (SdkException e) {
            log.error("S3FileStorage.delete FAILED bucket={} key={}: {}", bucket, key, e.getMessage(), e);
            throw new FileStorageException("STORAGE_DELETE_FAILED",
                    "Échec de suppression du fichier: " + truncate(e.getMessage()), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            log.error("S3FileStorage.exists FAILED bucket={} key={}: {}", bucket, key, e.getMessage(), e);
            throw new FileStorageException("STORAGE_READ_FAILED",
                    "Fichier illisible: " + truncate(e.getMessage()), e);
        }
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
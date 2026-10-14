package tn.hydra.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * An immutable file revision of a Document (Rev.0, Rev.A, Rev.B, ...).
 * The uploader is the audit "createdBy" and the upload time is "createdAt".
 */
@Entity
@Table(name = "document_revision")
@Getter
@Setter
@NoArgsConstructor
public class DocumentRevision extends Auditable {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "rev_label")
    private String revLabel;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(name = "change_note")
    private String changeNote;
}
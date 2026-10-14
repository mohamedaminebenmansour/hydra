package tn.hydra.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;
import tn.hydra.ticket.Ticket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A document that lives inside one folder of a project. The owning project is
 * derived from its folder (document -> folder -> project). Holds the current
 * revision pointer plus the full, ordered revision history. Supports an
 * optional link to a Ticket and many-to-many Tags.
 */
@Entity
@Table(name = "document")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Document extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;

    /** Tenant scope (denormalized for fast org-wide queries). */
    private UUID orgId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private DocFolder folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    /** Unique internal reference (see ReferenceGenerator), e.g. SONEDE-01-00007. */
    private String reference;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private DocType docType;

    @Column(name = "current_rev")
    private String currentRev;

    @Enumerated(EnumType.STRING)
    private DocStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    private List<DocumentRevision> revisions = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "document_tag",
            joinColumns = @JoinColumn(name = "document_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    // ---- convenience helpers keep both sides of the relations consistent ----

    public void addRevision(DocumentRevision revision) {
        revisions.add(revision);
        revision.setDocument(this);
        this.currentRev = revision.getRevLabel();
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }

    public void removeTag(Tag tag) {
        this.tags.remove(tag);
    }
}
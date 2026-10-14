package tn.hydra.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A project folder (one of the 7 fixed folders cloned on project creation,
 * or a nested one via parent_id). Documents live inside a folder.
 */
@Entity
@Table(name = "doc_folder")
@Getter
@Setter
@NoArgsConstructor
public class DocFolder {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DocFolder parent;

    private String code;
    private String name;
    private Integer sortOrder;
    private Instant createdAt;

    @OneToMany(mappedBy = "folder", fetch = FetchType.LAZY)
    private List<Document> documents;
}
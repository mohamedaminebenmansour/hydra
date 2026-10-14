package tn.hydra.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-oriented JPA view of the existing "project" table (created in V5/V8).
 * Provided so documents/folders have a clean JPA relationship. Project creation
 * and the automatic 7-folder cloning stay in tn.hydra.project.ProjectService.
 */
@Entity
@Table(name = "project")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    private UUID id;
    private UUID orgId;
    private String code;
    private String name;
    private String status;
    private String clientName;
    private String location;
    private String governorate;
    private LocalDate startDate;
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private ProjectType projectType;
    private String description;
    private BigDecimal contractValue;
    private String currency;

    @Enumerated(EnumType.STRING)
    private ProjectPhase phase;

    private UUID createdBy;
    private Instant createdAt;

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY)
    private List<DocFolder> folders = new ArrayList<>();
}
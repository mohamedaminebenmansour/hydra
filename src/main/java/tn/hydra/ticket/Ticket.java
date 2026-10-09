package tn.hydra.ticket;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket")
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "org_id") private UUID orgId;
    private String title;
    private String description;
    private String status;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolved_by") private UUID resolvedBy;

    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }           public void setOrgId(UUID v) { orgId = v; }
    public String getTitle() { return title; }         public void setTitle(String v) { title = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getStatus() { return status; }       public void setStatus(String v) { status = v; }
    public UUID getCreatedBy() { return createdBy; }   public void setCreatedBy(UUID v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getResolvedAt() { return resolvedAt; } public void setResolvedAt(Instant v) { resolvedAt = v; }
    public UUID getResolvedBy() { return resolvedBy; } public void setResolvedBy(UUID v) { resolvedBy = v; }
}
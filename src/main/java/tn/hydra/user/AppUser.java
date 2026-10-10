package tn.hydra.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    private String email;
    @Column(name = "password_hash") private String passwordHash;
    @Column(name = "full_name") private String fullName;
    @Column(name = "is_owner") private Boolean isOwner;
    @Column(name = "is_active") private Boolean isActive;
    @Column(name = "must_change_password") private Boolean mustChangePassword;
    @Column(name = "created_at") private Instant createdAt;

    public UUID getId() { return id; }                 public void setId(UUID v) { id = v; }
    public UUID getOrgId() { return orgId; }           public void setOrgId(UUID v) { orgId = v; }
    public String getEmail() { return email; }         public void setEmail(String v) { email = v; }
    public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
    public String getFullName() { return fullName; }   public void setFullName(String v) { fullName = v; }
    public Boolean getIsOwner() { return isOwner; }    public void setIsOwner(Boolean v) { isOwner = v; }
    public Boolean getIsActive() { return isActive; }  public void setIsActive(Boolean v) { isActive = v; }
    public Boolean getMustChangePassword() { return mustChangePassword; } public void setMustChangePassword(Boolean v) { mustChangePassword = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
}
package tn.hydra.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.document.domain.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Optional<Project> findByIdAndOrgId(UUID id, UUID orgId);
    List<Project> findByOrgIdOrderByNameAsc(UUID orgId);
    List<Project> findByOrgIdAndStatusOrderByNameAsc(UUID orgId, String status);
}
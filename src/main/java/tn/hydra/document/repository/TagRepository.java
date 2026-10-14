package tn.hydra.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.document.domain.Tag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {
    List<Tag> findByOrgIdOrderByNameAsc(UUID orgId);
    Optional<Tag> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<Tag> findByOrgIdAndNameIgnoreCase(UUID orgId, String name);
    boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);
}
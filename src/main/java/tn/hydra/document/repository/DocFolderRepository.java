package tn.hydra.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.document.domain.DocFolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocFolderRepository extends JpaRepository<DocFolder, UUID> {
    List<DocFolder> findByProject_IdOrderBySortOrderAscNameAsc(UUID projectId);
    Optional<DocFolder> findByIdAndProject_OrgId(UUID id, UUID orgId);
    long countByProject_Id(UUID projectId);
}
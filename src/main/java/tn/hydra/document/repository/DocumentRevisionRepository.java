package tn.hydra.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.document.domain.DocumentRevision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRevisionRepository extends JpaRepository<DocumentRevision, UUID> {

    List<DocumentRevision> findByDocument_IdOrderByCreatedAtAsc(UUID documentId);

    Optional<DocumentRevision> findTopByDocument_IdOrderByCreatedAtDesc(UUID documentId);

    Optional<DocumentRevision> findByDocument_IdAndRevLabel(UUID documentId, String revLabel);
}
package tn.hydra.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.document.domain.Document;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID>,
        JpaSpecificationExecutor<Document> {

    /**
     * Org-scoped fetch (soft-deleted rows are excluded by @SQLRestriction).
     * JOIN FETCH only to-one associations (folder → project). Collections
     * ({@code tags}, {@code revisions}) stay lazy and are initialized inside
     * the service transaction — fetching both collections in one query
     * cartesian-products a bag ({@code List} of revisions) with tags.
     */
    @Query("""
            select d from Document d
            join fetch d.folder f
            join fetch f.project
            where d.id = :id and d.orgId = :orgId
            """)
    Optional<Document> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Exact internal-reference lookup within the org (same eager graph as
     * {@link #findByIdAndOrgId}).
     */
    @Query("""
            select d from Document d
            join fetch d.folder f
            join fetch f.project
            where d.orgId = :orgId and lower(d.reference) = lower(:reference)
            """)
    Optional<Document> findByOrgIdAndReferenceIgnoreCase(@Param("orgId") UUID orgId,
                                                         @Param("reference") String reference);

    long countByFolder_Id(UUID folderId);
}

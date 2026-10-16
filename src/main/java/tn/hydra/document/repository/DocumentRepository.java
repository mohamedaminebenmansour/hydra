package tn.hydra.document.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.domain.Document;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Org-scoped fetch (soft-deleted rows are excluded by @SQLRestriction). */
    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);

    /** Exact internal-reference lookup within the org. */
    Optional<Document> findByOrgIdAndReferenceIgnoreCase(UUID orgId, String reference);

    long countByFolder_Id(UUID folderId);

    /**
     * Search with NO folder ACL filter (caller guarantees readable == null, i.e. owner/admin).
     * Never binds a null/empty collection to IN — avoids PostgreSQL "in ()" syntax errors.
     */
    @Query("""
            select d from Document d
            where d.orgId = :orgId
              and (:folderId is null or d.folder.id = :folderId)
              and (:projectId is null or d.folder.project.id = :projectId)
              and (:type is null or d.docType = :type)
              and (:status is null or d.status = :status)
              and (:revision is null or d.currentRev = :revision)
              and (:uploaderId is null or d.createdBy = :uploaderId)
              and (:dateFrom is null or d.createdAt >= :dateFrom)
              and (:dateTo is null or d.createdAt <= :dateTo)
              and (:tagId is null or exists (select 1 from d.tags t where t.id = :tagId))
              and (:q is null or lower(d.title) like lower(concat('%', :q, '%'))
                              or lower(d.reference) like lower(concat('%', :q, '%')))
            order by d.createdAt desc
            """)
    Page<Document> searchAll(@Param("orgId") UUID orgId,
                             @Param("folderId") UUID folderId,
                             @Param("projectId") UUID projectId,
                             @Param("type") DocType type,
                             @Param("status") DocStatus status,
                             @Param("tagId") UUID tagId,
                             @Param("q") String q,
                             @Param("dateFrom") Instant dateFrom,
                             @Param("dateTo") Instant dateTo,
                             @Param("uploaderId") UUID uploaderId,
                             @Param("revision") String revision,
                             Pageable pageable);

    /**
     * Search restricted to readable folders (caller guarantees non-empty folderIds).
     * Binds d.folder.id in :folderIds directly — never "is null or ... in :collection".
     */
    @Query("""
            select d from Document d
            where d.orgId = :orgId
              and (:folderId is null or d.folder.id = :folderId)
              and (:projectId is null or d.folder.project.id = :projectId)
              and d.folder.id in :folderIds
              and (:type is null or d.docType = :type)
              and (:status is null or d.status = :status)
              and (:revision is null or d.currentRev = :revision)
              and (:uploaderId is null or d.createdBy = :uploaderId)
              and (:dateFrom is null or d.createdAt >= :dateFrom)
              and (:dateTo is null or d.createdAt <= :dateTo)
              and (:tagId is null or exists (select 1 from d.tags t where t.id = :tagId))
              and (:q is null or lower(d.title) like lower(concat('%', :q, '%'))
                              or lower(d.reference) like lower(concat('%', :q, '%')))
            order by d.createdAt desc
            """)
    Page<Document> searchInFolders(@Param("orgId") UUID orgId,
                                   @Param("folderId") UUID folderId,
                                   @Param("projectId") UUID projectId,
                                   @Param("type") DocType type,
                                   @Param("status") DocStatus status,
                                   @Param("tagId") UUID tagId,
                                   @Param("q") String q,
                                   @Param("dateFrom") Instant dateFrom,
                                   @Param("dateTo") Instant dateTo,
                                   @Param("uploaderId") UUID uploaderId,
                                   @Param("revision") String revision,
                                   @Param("folderIds") java.util.Collection<UUID> folderIds,
                                   Pageable pageable);

    /** Master Document Register: all documents of a project, no ACL filter (readable == null). */
    @Query("""
            select d from Document d
            where d.orgId = :orgId and d.folder.project.id = :projectId
            order by d.reference asc
            """)
    Page<Document> registerAll(@Param("orgId") UUID orgId,
                               @Param("projectId") UUID projectId,
                               Pageable pageable);

    /** Master Document Register restricted to readable folders (non-empty folderIds). */
    @Query("""
            select d from Document d
            where d.orgId = :orgId and d.folder.project.id = :projectId
              and d.folder.id in :folderIds
            order by d.reference asc
            """)
    Page<Document> registerInFolders(@Param("orgId") UUID orgId,
                                     @Param("projectId") UUID projectId,
                                     @Param("folderIds") java.util.Collection<UUID> folderIds,
                                     Pageable pageable);
}
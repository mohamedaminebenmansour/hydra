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

    /**
     * Org-scoped fetch (soft-deleted rows are excluded by @SQLRestriction).
     * SELECT DISTINCT + JOIN FETCH (folder → project, tags, revisions) so get() /
     * byReference() can map to a DTO without any lazy access — required because
     * spring.jpa.open-in-view=false. Hibernate de-duplicates entity results in memory.
     */
    @Query("""
            select distinct d from Document d
            join fetch d.folder f
            join fetch f.project
            left join fetch d.tags
            left join fetch d.revisions
            where d.id = :id and d.orgId = :orgId
            """)
    Optional<Document> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Exact internal-reference lookup within the org (same eager graph as
     * {@link #findByIdAndOrgId}).
     */
    @Query("""
            select distinct d from Document d
            join fetch d.folder f
            join fetch f.project
            left join fetch d.tags
            left join fetch d.revisions
            where d.orgId = :orgId and lower(d.reference) = lower(:reference)
            """)
    Optional<Document> findByOrgIdAndReferenceIgnoreCase(@Param("orgId") UUID orgId,
                                                         @Param("reference") String reference);

    long countByFolder_Id(UUID folderId);

    /**
     * Search with NO folder ACL filter (caller guarantees readable == null, i.e. owner/admin).
     * Never binds a null/empty collection to IN — avoids PostgreSQL "in ()" syntax errors.
     *
     * <p>Eager graph: SELECT DISTINCT + JOIN FETCH folder→project + LEFT JOIN FETCH
     * tags/revisions so DocumentMapper.toResponse never triggers lazy loading
     * (spring.jpa.open-in-view=false). Explicit countQuery without fetch joins:
     * no row multiplication there, so count(d) is exact.
     */
    @Query(
            value = """
                    select distinct d from Document d
                    join fetch d.folder f
                    join fetch f.project
                    left join fetch d.tags
                    left join fetch d.revisions
                    where d.orgId = :orgId
                      and (:folderId is null or f.id = :folderId)
                      and (:projectId is null or f.project.id = :projectId)
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
                    """,
            countQuery = """
                    select count(d) from Document d
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
     * Binds f.id in :folderIds directly — never "is null or ... in :collection".
     * Same eager graph + explicit countQuery as {@link #searchAll}.
     */
    @Query(
            value = """
                    select distinct d from Document d
                    join fetch d.folder f
                    join fetch f.project
                    left join fetch d.tags
                    left join fetch d.revisions
                    where d.orgId = :orgId
                      and (:folderId is null or f.id = :folderId)
                      and (:projectId is null or f.project.id = :projectId)
                      and f.id in :folderIds
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
                    """,
            countQuery = """
                    select count(d) from Document d
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

    /**
     * Master Document Register: all documents of a project, no ACL filter (readable == null).
     * Same eager graph + explicit countQuery as {@link #searchAll}.
     */
    @Query(
            value = """
                    select distinct d from Document d
                    join fetch d.folder f
                    join fetch f.project
                    left join fetch d.tags
                    left join fetch d.revisions
                    where d.orgId = :orgId and f.project.id = :projectId
                    order by d.reference asc
                    """,
            countQuery = """
                    select count(d) from Document d
                    where d.orgId = :orgId and d.folder.project.id = :projectId
                    """)
    Page<Document> registerAll(@Param("orgId") UUID orgId,
                               @Param("projectId") UUID projectId,
                               Pageable pageable);

    /**
     * Master Document Register restricted to readable folders (non-empty folderIds).
     * Same eager graph + explicit countQuery as {@link #searchAll}.
     */
    @Query(
            value = """
                    select distinct d from Document d
                    join fetch d.folder f
                    join fetch f.project
                    left join fetch d.tags
                    left join fetch d.revisions
                    where d.orgId = :orgId and f.project.id = :projectId
                      and f.id in :folderIds
                    order by d.reference asc
                    """,
            countQuery = """
                    select count(d) from Document d
                    where d.orgId = :orgId and d.folder.project.id = :projectId
                      and d.folder.id in :folderIds
                    """)
    Page<Document> registerInFolders(@Param("orgId") UUID orgId,
                                     @Param("projectId") UUID projectId,
                                     @Param("folderIds") java.util.Collection<UUID> folderIds,
                                     Pageable pageable);
}
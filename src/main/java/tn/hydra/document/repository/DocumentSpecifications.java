package tn.hydra.document.repository;

import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.Tag;
import tn.hydra.document.dto.DocumentSearchQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Criteria predicates for document list/register.
 *
 * <p>Paged queries must <b>not</b> {@code JOIN FETCH} collections ({@code tags},
 * {@code revisions}): Hibernate 6.2+/7 refuses pagination over collection fetches
 * ({@code fail_on_pagination_over_collection_fetch}), which surfaced as HTTP 500
 * on {@code GET /api/projects/{id}/documents?page=&size=}. Only to-one associations
 * (folder → project) are fetched. Optional filters are omitted when null so
 * PostgreSQL never receives an untyped {@code NULL} bind.
 */
public final class DocumentSpecifications {

    private DocumentSpecifications() {}

    public static Specification<Document> search(UUID orgId, DocumentSearchQuery q,
                                                 Collection<UUID> readableFolderIds) {
        return (root, query, cb) -> {
            Join<Document, DocFolder> folder = folderJoin(root, query);
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("orgId"), orgId));
            if (q.folderId() != null) {
                preds.add(cb.equal(folder.get("id"), q.folderId()));
            }
            if (q.projectId() != null) {
                preds.add(cb.equal(folder.get("project").get("id"), q.projectId()));
            }
            if (readableFolderIds != null) {
                preds.add(folder.get("id").in(readableFolderIds));
            }
            if (q.type() != null) {
                preds.add(cb.equal(root.get("docType"), q.type()));
            }
            if (q.status() != null) {
                preds.add(cb.equal(root.get("status"), q.status()));
            }
            if (q.revision() != null && !q.revision().isBlank()) {
                preds.add(cb.equal(root.get("currentRev"), q.revision()));
            }
            if (q.uploaderId() != null) {
                preds.add(cb.equal(root.get("createdBy"), q.uploaderId()));
            }
            if (q.dateFrom() != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), q.dateFrom()));
            }
            if (q.dateTo() != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), q.dateTo()));
            }
            if (q.tagId() != null) {
                Join<Document, Tag> tag = root.join("tags", JoinType.INNER);
                preds.add(cb.equal(tag.get("id"), q.tagId()));
                query.distinct(true);
            }
            if (q.q() != null && !q.q().isBlank()) {
                String pattern = "%" + q.q().trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("reference")), pattern)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    public static Specification<Document> register(UUID orgId, UUID projectId,
                                                   Collection<UUID> readableFolderIds) {
        return (root, query, cb) -> {
            Join<Document, DocFolder> folder = folderJoin(root, query);
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("orgId"), orgId));
            preds.add(cb.equal(folder.get("project").get("id"), projectId));
            if (readableFolderIds != null) {
                preds.add(folder.get("id").in(readableFolderIds));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    /**
     * Fetch folder→project on the data query (safe with LIMIT/OFFSET). Skip fetch
     * on the count query so Spring Data's {@code count(*)} does not multiply rows.
     */
    @SuppressWarnings("unchecked")
    private static Join<Document, DocFolder> folderJoin(Root<Document> root, CriteriaQuery<?> query) {
        if (!isCountQuery(query)) {
            Fetch<Document, DocFolder> fetch = root.fetch("folder", JoinType.INNER);
            fetch.fetch("project", JoinType.INNER);
            return (Join<Document, DocFolder>) fetch;
        }
        return root.join("folder", JoinType.INNER);
    }

    private static boolean isCountQuery(CriteriaQuery<?> query) {
        Class<?> resultType = query.getResultType();
        return resultType != null && (Number.class.isAssignableFrom(resultType)
                || resultType == long.class
                || resultType == int.class);
    }
}

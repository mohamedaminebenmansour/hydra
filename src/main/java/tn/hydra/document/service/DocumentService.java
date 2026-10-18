package tn.hydra.document.service;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.PageResponse;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.DocumentRevision;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.domain.Tag;
import tn.hydra.document.dto.CreateDocumentRequest;
import tn.hydra.document.dto.DocumentMapper;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.dto.DocumentSearchQuery;
import tn.hydra.document.dto.LinkTicketRequest;
import tn.hydra.document.dto.UpdateDocumentRequest;
import tn.hydra.document.repository.DocFolderRepository;
import tn.hydra.document.repository.DocumentRepository;
import tn.hydra.document.repository.DocumentSpecifications;
import tn.hydra.document.repository.TagRepository;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.security.FolderAccessService;
import tn.hydra.security.FolderAccessService.FolderAction;
import tn.hydra.ticket.Ticket;
import tn.hydra.ticket.TicketRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;



import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documents;
    private final DocFolderRepository folders;
    private final TagRepository tags;
    private final TicketRepository ticketRepo;
    private final AppUserRepository users;
    private final DocumentMapper mapper;
    private final EventLogService audit;
    private final ReferenceGenerator references;
    private final FileStorage storage;
    private final FilenameBuilder filenameBuilder;
    private final JdbcTemplate jdbc;
    private final FolderAccessService folderAccess;

    @PersistenceContext
    private EntityManager em;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx",
            "png", "jpg", "jpeg", "gif", "webp",
            "dwg", "dxf", "zip", "txt", "csv"
    );

    public DocumentService(DocumentRepository documents, DocFolderRepository folders,
                           TagRepository tags, TicketRepository ticketRepo,
                           AppUserRepository users, DocumentMapper mapper, EventLogService audit,
                           ReferenceGenerator references, FileStorage storage,
                           FilenameBuilder filenameBuilder, JdbcTemplate jdbc,
                           FolderAccessService folderAccess) {
        this.documents = documents; this.folders = folders; this.tags = tags;
        this.ticketRepo = ticketRepo; this.users = users; this.mapper = mapper;
        this.audit = audit; this.references = references; this.storage = storage;
        this.filenameBuilder = filenameBuilder; this.jdbc = jdbc;
        this.folderAccess = folderAccess;
    }

    // ── combined upload: create Document + first revision in one call ─────
    @Transactional
    public DocumentResponse upload(Authentication auth, UUID folderId, String title,
                                   DocType docType, String revision, UUID ticketId,
                                   List<String> tagNames, String description, MultipartFile file) {
        String userEmail = auth == null ? "anonymous" : auth.getName();
        log.info("[DOC-UPLOAD] entry folderId={} title={} docType={} revision={} filename={} size={} user={}",
                folderId, title, docType, revision,
                file == null ? null : file.getOriginalFilename(),
                file == null ? -1L : file.getSize(), userEmail);
        AppUser me = currentUser(auth);
        if (title == null || title.isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (file == null || file.isEmpty())
            throw new BadRequestException("FILE_REQUIRED", "Un fichier est requis");

        // File type validation by extension (MIME is unreliable)
        String ext = FilenameBuilder.extension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
            throw new BadRequestException("FILE_TYPE_NOT_ALLOWED",
                    "Type de fichier non autorisé. Formats acceptés : PDF, Word, Excel, images, DWG/DXF, ZIP");
        }

        // Business rule: the document must live in a folder of a project of the caller's org.
        DocFolder folder = folders.findByIdAndProject_OrgId(folderId, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable"));
        log.info("[DOC-UPLOAD] folder loaded folderId={} projectId={} code={} isOwner={}",
                folder.getId(), folder.getProject().getId(), folder.getCode(),
                Boolean.TRUE.equals(me.getIsOwner()));
        folderAccess.assertAccess(me.getId(), folderId, FolderAction.UPLOAD);
        log.info("[DOC-UPLOAD] assertAccess UPLOAD OK folderId={} userId={}", folderId, me.getId());
        assertCanUpload(auth, me, folder.getProject().getId());
        log.info("[DOC-UPLOAD] assertCanUpload OK isOwner={} projectId={}",
                Boolean.TRUE.equals(me.getIsOwner()), folder.getProject().getId());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("FILE_UNREADABLE", "Fichier illisible");
        }

        String revLabel = (revision == null || revision.isBlank()) ? "Rev.0" : revision.trim();
        String projectCode = folder.getProject().getCode();

        // 1. Pre-generate IDs to avoid multi-save session flushing bugs
        UUID docId = UUID.randomUUID();
        UUID revId = UUID.randomUUID();

        // 2. Build final storage key and store file using the pre-generated docId
        String fileName = filenameBuilder.build(projectCode, docType, title.trim(), revLabel,
                file.getOriginalFilename());
        String finalKey = "org/" + me.getOrgId() + "/project/" + folder.getProject().getId()
                + "/" + folder.getCode() + "/doc/" + docId + "/"
                + revLabel.replace(' ', '_') + "__" + fileName;

        log.info("[DOC-UPLOAD] before storage.save key={} bytes={} contentType={} storage={}",
                finalKey, bytes.length, file.getContentType(), storage.getClass().getName());
        StoredFile stored = storage.save(finalKey, bytes, file.getContentType());
        log.info("[DOC-UPLOAD] storage.save OK key={} size={} checksum={}",
                stored.key(), stored.size(), stored.checksumSha256());

        // 3. Assemble the complete Document and Revision graph together atomically
        Document doc = new Document();
        doc.setId(docId);
        doc.setOrgId(me.getOrgId());
        doc.setFolder(folder);
        doc.setReference(references.next(projectCode));
        doc.setTitle(title.trim());
        doc.setDescription(description);
        doc.setDocType(docType);
        doc.setStatus(DocStatus.DRAFT);
        doc.setTicket(loadTicket(ticketId, me.getOrgId()));
        applyTags(doc, tagNames, me);

        DocumentRevision rev = new DocumentRevision();
        rev.setId(revId);
        rev.setRevLabel(revLabel);
        rev.setFileName(fileName);
        rev.setStorageKey(stored.key());
        rev.setMimeType(file.getContentType());
        rev.setSizeBytes(stored.size());
        rev.setChecksumSha256(stored.checksumSha256());
        rev.setChangeNote(null);

        doc.addRevision(rev); // Links both sides and sets currentRev

        doc.markNew();
        rev.markNew();

        // ALWAYS persist for pre-assigned UUIDs - never repository.save() here
        try {
            em.persist(doc);
            em.flush();
        } catch (RuntimeException e) {
            log.error("[DOC-UPLOAD] DB save FAILED documentId={}: {} — {}",
                    docId, e.getClass().getSimpleName(), e.getMessage(), e);
            // Best-effort cleanup: remove the already-uploaded S3 object to avoid an orphan.
            try {
                storage.delete(finalKey);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        log.info("[DOC-UPLOAD] DB save OK documentId={} reference={} storageKey={}",
                doc.getId(), doc.getReference(), stored.key());

        audit.log("DOCUMENT_UPLOADED", "document", doc.getId(),
                "{\"reference\":\"" + esc(doc.getReference()) + "\",\"rev\":\"" + revLabel + "\"}");
        return toResponse(doc);
    }

    // ── create (metadata only, no file) ───────────────────────────────────
    @Transactional
    public DocumentResponse create(Authentication auth, UUID folderId, CreateDocumentRequest req) {
        AppUser me = currentUser(auth);
        DocFolder folder = folders.findByIdAndProject_OrgId(folderId, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable"));
        folderAccess.assertAccess(me.getId(), folderId, FolderAction.UPLOAD);
        assertCanUpload(auth, me, folder.getProject().getId());

        Document doc = new Document();
        doc.setOrgId(me.getOrgId());
        doc.setFolder(folder);
        doc.setReference(references.next(folder.getProject().getCode()));
        doc.setTitle(req.title().trim());
        doc.setDescription(req.description());
        doc.setDocType(req.docType());
        doc.setStatus(DocStatus.DRAFT);
        doc.setTicket(loadTicket(req.ticketId(), me.getOrgId()));
        applyTags(doc, req.tagNames(), me);

        documents.save(doc);
        audit.log("DOCUMENT_CREATED", "document", doc.getId(),
                "{\"reference\":\"" + esc(doc.getReference()) + "\"}");
        return toResponse(doc);
    }

    // ── read ─────────────────────────────────────────────────────────────
    public DocumentResponse get(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        try {
            Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
            folderAccess.assertAccess(me.getId(), doc.getFolder().getId(), FolderAction.READ);
            DocumentResponse response = toResponse(doc);
            log.info("[DOC-GET] user={} org={} documentId={} folderId={}",
                    me.getEmail(), me.getOrgId(), id, response.folderId());
            return response;
        } catch (RuntimeException e) {
            log.error("[DOC-GET] FAILED user={} org={} documentId={}: {} — {}",
                    me.getEmail(), me.getOrgId(), id,
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    public DocumentResponse byReference(Authentication auth, String reference) {
        AppUser me = currentUser(auth);
        try {
            Document doc = documents.findByOrgIdAndReferenceIgnoreCase(me.getOrgId(), reference)
                    .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
            folderAccess.assertAccess(me.getId(), doc.getFolder().getId(), FolderAction.READ);
            DocumentResponse response = toResponse(doc);
            log.info("[DOC-BYREF] user={} org={} reference={} folderId={}",
                    me.getEmail(), me.getOrgId(), reference, response.folderId());
            return response;
        } catch (RuntimeException e) {
            log.error("[DOC-BYREF] FAILED user={} org={} reference={}: {} — {}",
                    me.getEmail(), me.getOrgId(), reference,
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    public PageResponse<DocumentResponse> search(Authentication auth, DocumentSearchQuery q, Pageable pageable) {
        AppUser me = currentUser(auth);
        // folder ACL: null = no filter (owner / project ADMIN)
        Set<UUID> readable = folderAccess.readableFolderIds(me, q.projectId());
        log.info("[DOC-SEARCH] user={} org={} projectId={} folderId={} readableNull={}",
                me.getEmail(), me.getOrgId(), q.projectId(), q.folderId(), readable == null);
        if (readable != null && readable.isEmpty())
            return PageResponse.from(Page.empty(pageable));

        Pageable sorted = withDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "createdAt"));
        try {
            Page<Document> page = documents.findAll(
                    DocumentSpecifications.search(me.getOrgId(), q, readable), sorted);
            List<DocumentResponse> content = toResponses(page.getContent());
            log.info("[DOC-SEARCH] page size={} total={}", content.size(), page.getTotalElements());
            return new PageResponse<>(content, page.getTotalElements(),
                    page.getNumber(), page.getSize());
        } catch (RuntimeException e) {
            log.error("[DOC-SEARCH] FAILED user={} org={} projectId={} folderId={}: {} — {}",
                    me.getEmail(), me.getOrgId(), q.projectId(), q.folderId(),
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    /** Master Document Register: all documents of a project, ordered by reference. */
    public PageResponse<DocumentResponse> register(Authentication auth, UUID projectId, Pageable pageable) {
        AppUser me = currentUser(auth);
        Set<UUID> readable = folderAccess.readableFolderIds(me, projectId);
        log.info("[DOC-REGISTER] user={} org={} projectId={} readableNull={}",
                me.getEmail(), me.getOrgId(), projectId, readable == null);
        if (readable != null && readable.isEmpty())
            return PageResponse.from(Page.empty(pageable));

        Pageable sorted = withDefaultSort(pageable, Sort.by(Sort.Direction.ASC, "reference"));
        try {
            Page<Document> page = documents.findAll(
                    DocumentSpecifications.register(me.getOrgId(), projectId, readable), sorted);
            List<DocumentResponse> content = toResponses(page.getContent());
            log.info("[DOC-REGISTER] page size={} total={}", content.size(), page.getTotalElements());
            return new PageResponse<>(content, page.getTotalElements(),
                    page.getNumber(), page.getSize());
        } catch (RuntimeException e) {
            log.error("[DOC-REGISTER] FAILED user={} org={} projectId={}: {} — {}",
                    me.getEmail(), me.getOrgId(), projectId,
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    // ── update (partial) ─────────────────────────────────────────────────
    @Transactional
    public DocumentResponse update(Authentication auth, UUID id, UpdateDocumentRequest req) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));

        if (req.title() != null && !req.title().isBlank()) doc.setTitle(req.title().trim());
        if (req.description() != null) doc.setDescription(req.description());
        if (req.docType() != null) doc.setDocType(req.docType());
        if (req.status() != null) doc.setStatus(req.status());
        if (req.ticketId() != null) doc.setTicket(loadTicket(req.ticketId(), me.getOrgId()));
        if (req.tagNames() != null) applyTags(doc, req.tagNames(), me);

        documents.save(doc);
        audit.log("DOCUMENT_UPDATED", "document", doc.getId(),
                "{\"reference\":\"" + esc(doc.getReference()) + "\"}");
        return toResponse(doc);
    }

    // ── link / unlink ticket ─────────────────────────────────────────────
    @Transactional
    public DocumentResponse linkTicket(Authentication auth, UUID id, LinkTicketRequest req) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        doc.setTicket(loadTicket(req.ticketId(), me.getOrgId())); // null ticketId => unlink
        documents.save(doc);
        audit.log("DOCUMENT_TICKET_LINKED", "document", doc.getId(),
                "{\"ticketId\":" + (req.ticketId() == null ? "null" : "\"" + req.ticketId() + "\"") + "}");
        return toResponse(doc);
    }

    // ── soft delete ──────────────────────────────────────────────────────
    @Transactional
    public void softDelete(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        folderAccess.assertAccess(me.getId(), doc.getFolder().getId(), FolderAction.DELETE);
        doc.setDeletedAt(Instant.now());
        if (doc.getStatus() != DocStatus.ARCHIVED) doc.setStatus(DocStatus.ARCHIVED);
        documents.save(doc);
        audit.log("DOCUMENT_DELETED", "document", doc.getId(), null);
    }

    // ── helpers ──────────────────────────────────────────────────────────
    /**
     * Map inside the open persistence context. Tags/revisions are lazy; initialize
     * them here (batched via {@code @BatchSize}) so MapStruct never hits a closed session.
     */
    private List<DocumentResponse> toResponses(List<Document> docs) {
        return docs.stream().map(this::toResponse).toList();
    }

    private DocumentResponse toResponse(Document doc) {
        Hibernate.initialize(doc.getTags());
        Hibernate.initialize(doc.getRevisions());
        return mapper.toResponse(doc);
    }

    private static Pageable withDefaultSort(Pageable pageable, Sort fallback) {
        if (pageable.getSort().isSorted()) return pageable;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), fallback);
    }

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "Utilisateur introuvable"));
    }

    /**
     * Per-project upload gate on top of the global PERM_DOCUMENT_UPLOAD (enforced by
     * @PreAuthorize). The org owner may always upload. Otherwise the caller must be a
     * project member with can_upload = true, or hold an ADMIN / MANAGER project role.
     */
    void assertCanUpload(Authentication auth, AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;

        Boolean allowed = jdbc.query("""
                select (can_upload = true or project_role in ('ADMIN','MANAGER')) as ok
                from project_member
                where project_id = ? and user_id = ?
                """, rs -> rs.next() ? rs.getBoolean("ok") : false, projectId, me.getId());

        if (!Boolean.TRUE.equals(allowed))
            throw new tn.hydra.common.BusinessRuleException("UPLOAD_NOT_ALLOWED",
                    "Vous n'avez pas le droit de téléverser dans ce projet");
    }

    private Ticket loadTicket(UUID ticketId, UUID orgId) {
        if (ticketId == null) return null;
        return ticketRepo.findByIdAndOrgId(ticketId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket introuvable"));
    }

    /** Replace the tag set; find-or-create tags by (org, name). */
    private void applyTags(Document doc, List<String> names, AppUser me) {
        if (names == null) return;
        doc.getTags().clear();
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String n : names) if (n != null && !n.isBlank()) uniq.add(n.trim());
        for (String n : uniq) {
            Tag tag = tags.findByOrgIdAndNameIgnoreCase(me.getOrgId(), n).orElseGet(() -> {
                Tag t = new Tag();
                t.setOrgId(me.getOrgId());
                t.setName(n);
                return tags.save(t);
            });
            doc.addTag(tag);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}



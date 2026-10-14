package tn.hydra.document.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import tn.hydra.document.repository.TagRepository;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.ticket.Ticket;
import tn.hydra.ticket.TicketRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DocumentService {

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

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx",
            "png", "jpg", "jpeg", "gif", "webp",
            "dwg", "dxf", "zip", "txt", "csv"
    );

    public DocumentService(DocumentRepository documents, DocFolderRepository folders,
                           TagRepository tags, TicketRepository ticketRepo,
                           AppUserRepository users, DocumentMapper mapper, EventLogService audit,
                           ReferenceGenerator references, FileStorage storage,
                           FilenameBuilder filenameBuilder, JdbcTemplate jdbc) {
        this.documents = documents; this.folders = folders; this.tags = tags;
        this.ticketRepo = ticketRepo; this.users = users; this.mapper = mapper;
        this.audit = audit; this.references = references; this.storage = storage;
        this.filenameBuilder = filenameBuilder; this.jdbc = jdbc;
    }

    // ── combined upload: create Document + first revision in one call ─────
    @Transactional
    public DocumentResponse upload(Authentication auth, UUID folderId, String title,
                                   DocType docType, String revision, UUID ticketId,
                                   List<String> tagNames, String description, MultipartFile file) {
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
        assertCanUpload(auth, me, folder.getProject().getId());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("FILE_UNREADABLE", "Fichier illisible");
        }

        String revLabel = (revision == null || revision.isBlank()) ? "Rev.0" : revision.trim();
        String projectCode = folder.getProject().getCode();

        // Create document FIRST (without revision) to get generated ID
        Document doc = new Document();
        doc.setOrgId(me.getOrgId());
        doc.setFolder(folder);
        doc.setReference(references.next(projectCode));
        doc.setTitle(title.trim());
        doc.setDescription(description);
        doc.setDocType(docType);
        doc.setStatus(DocStatus.DRAFT);
        doc.setTicket(loadTicket(ticketId, me.getOrgId()));
        applyTags(doc, tagNames, me);

        documents.save(doc); // assigns id via @UuidGenerator

        // Now we have doc.getId() - build final storage key and store file
        String fileName = filenameBuilder.build(projectCode, docType, title.trim(), revLabel,
                file.getOriginalFilename());
        String finalKey = "org/" + me.getOrgId() + "/project/" + folder.getProject().getId()
                + "/" + folder.getCode() + "/doc/" + doc.getId() + "/"
                + revLabel.replace(' ', '_') + "__" + fileName;

        StoredFile stored = storage.save(finalKey, bytes, file.getContentType());

        // Create revision with storage key (NEVER null)
        DocumentRevision rev = new DocumentRevision();
        rev.setRevLabel(revLabel);
        rev.setFileName(fileName);
        rev.setStorageKey(stored.key());
        rev.setMimeType(file.getContentType());
        rev.setSizeBytes(stored.size());
        rev.setChecksumSha256(stored.checksumSha256());
        rev.setChangeNote(null);
        doc.addRevision(rev); // sets currentRev

        documents.save(doc); // cascade persists the revision

        audit.log("DOCUMENT_UPLOADED", "document", doc.getId(),
                "{\"reference\":\"" + esc(doc.getReference()) + "\",\"rev\":\"" + revLabel + "\"}");
        return mapper.toResponse(doc);
    }

    // ── create (metadata only, no file) ───────────────────────────────────
    @Transactional
    public DocumentResponse create(Authentication auth, UUID folderId, CreateDocumentRequest req) {
        AppUser me = currentUser(auth);
        DocFolder folder = folders.findByIdAndProject_OrgId(folderId, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable"));
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
        return mapper.toResponse(doc);
    }

    // ── read ─────────────────────────────────────────────────────────────
    public DocumentResponse get(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        return mapper.toResponse(doc);
    }

    public DocumentResponse byReference(Authentication auth, String reference) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByOrgIdAndReferenceIgnoreCase(me.getOrgId(), reference)
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        return mapper.toResponse(doc);
    }

    public PageResponse<DocumentResponse> search(Authentication auth, DocumentSearchQuery q, Pageable pageable) {
        AppUser me = currentUser(auth);
        Page<Document> page = documents.search(me.getOrgId(), q.folderId(), q.projectId(),
                q.type(), q.status(), q.tagId(), q.q(), q.dateFrom(), q.dateTo(),
                q.uploaderId(), q.revision(), pageable);
        return PageResponse.from(page.map(mapper::toResponse));
    }

    /** Master Document Register: all documents of a project, ordered by reference. */
    public PageResponse<DocumentResponse> register(Authentication auth, UUID projectId, Pageable pageable) {
        AppUser me = currentUser(auth);
        Page<Document> page = documents.register(me.getOrgId(), projectId, pageable);
        return PageResponse.from(page.map(mapper::toResponse));
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
        return mapper.toResponse(doc);
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
        return mapper.toResponse(doc);
    }

    // ── soft delete ──────────────────────────────────────────────────────
    @Transactional
    public void softDelete(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        doc.setDeletedAt(Instant.now());
        if (doc.getStatus() != DocStatus.ARCHIVED) doc.setStatus(DocStatus.ARCHIVED);
        documents.save(doc);
        audit.log("DOCUMENT_DELETED", "document", doc.getId(), null);
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow();
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
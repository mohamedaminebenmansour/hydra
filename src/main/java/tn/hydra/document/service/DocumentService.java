package tn.hydra.document.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.PageResponse;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.Document;
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
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.ticket.Ticket;
import tn.hydra.ticket.TicketRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
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

    public DocumentService(DocumentRepository documents, DocFolderRepository folders,
                           TagRepository tags, TicketRepository ticketRepo,
                           AppUserRepository users, DocumentMapper mapper, EventLogService audit) {
        this.documents = documents; this.folders = folders; this.tags = tags;
        this.ticketRepo = ticketRepo; this.users = users; this.mapper = mapper; this.audit = audit;
    }

    // ── create ───────────────────────────────────────────────────────────
    @Transactional
    public DocumentResponse create(Authentication auth, UUID folderId, CreateDocumentRequest req) {
        AppUser me = currentUser(auth);
        // Business rule: the document must live in a folder of a project of the caller's org.
        DocFolder folder = folders.findByIdAndProject_OrgId(folderId, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable"));

        Document doc = new Document();
        doc.setOrgId(me.getOrgId());
        doc.setFolder(folder);
        doc.setTitle(req.title().trim());
        doc.setDocType(req.docType());
        doc.setStatus(DocStatus.DRAFT);
        doc.setTicket(loadTicket(req.ticketId(), me.getOrgId()));
        applyTags(doc, req.tagNames(), me);

        documents.save(doc);
        audit.log("DOCUMENT_CREATED", "document", doc.getId(),
                "{\"title\":\"" + esc(doc.getTitle()) + "\"}");
        return mapper.toResponse(doc);
    }

    // ── read ─────────────────────────────────────────────────────────────
    public DocumentResponse get(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        return mapper.toResponse(doc);
    }

    public PageResponse<DocumentResponse> search(Authentication auth, DocumentSearchQuery q, Pageable pageable) {
        AppUser me = currentUser(auth);
        Page<Document> page = documents.search(me.getOrgId(), q.folderId(), q.projectId(),
                q.type(), q.status(), q.tagId(), q.q(), pageable);
        return PageResponse.from(page.map(mapper::toResponse));
    }

    // ── update (partial) ─────────────────────────────────────────────────
    @Transactional
    public DocumentResponse update(Authentication auth, UUID id, UpdateDocumentRequest req) {
        AppUser me = currentUser(auth);
        Document doc = documents.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));

        if (req.title() != null && !req.title().isBlank()) doc.setTitle(req.title().trim());
        if (req.docType() != null) doc.setDocType(req.docType());
        if (req.status() != null) doc.setStatus(req.status());
        if (req.ticketId() != null) doc.setTicket(loadTicket(req.ticketId(), me.getOrgId()));
        if (req.tagNames() != null) applyTags(doc, req.tagNames(), me);

        documents.save(doc);
        audit.log("DOCUMENT_UPDATED", "document", doc.getId(),
                "{\"title\":\"" + esc(doc.getTitle()) + "\"}");
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
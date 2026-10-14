package tn.hydra.document.controller;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.PageResponse;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.dto.CreateDocumentRequest;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.dto.DocumentSearchQuery;
import tn.hydra.document.dto.LinkTicketRequest;
import tn.hydra.document.dto.RevisionResponse;
import tn.hydra.document.dto.UpdateDocumentRequest;
import tn.hydra.document.service.DocumentService;
import tn.hydra.document.service.DownloadFile;
import tn.hydra.document.service.RevisionService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class DocumentController {

    private final DocumentService documents;
    private final RevisionService revisions;

    public DocumentController(DocumentService documents, RevisionService revisions) {
        this.documents = documents; this.revisions = revisions;
    }

    // ── create / search ──────────────────────────────────────────────────
    @PostMapping("/api/projects/{projectId}/folders/{folderId}/documents")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<DocumentResponse> create(@PathVariable UUID projectId,
                                                   @PathVariable UUID folderId,
                                                   @RequestBody CreateDocumentRequest body,
                                                   Authentication auth) {
        DocumentResponse created = documents.create(auth, folderId, body);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/api/projects/{projectId}/documents")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public PageResponse<DocumentResponse> search(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) DocType type,
            @RequestParam(required = false) DocStatus status,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String q,
            Pageable pageable,
            Authentication auth) {
        DocumentSearchQuery query = new DocumentSearchQuery(folderId, projectId, type, status, tagId, q);
        return documents.search(auth, query, pageable);
    }

    // ── single document ──────────────────────────────────────────────────
    @GetMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public DocumentResponse get(@PathVariable UUID id, Authentication auth) {
        return documents.get(auth, id);
    }

    @PutMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentResponse update(@PathVariable UUID id,
                                   @RequestBody UpdateDocumentRequest body,
                                   Authentication auth) {
        return documents.update(auth, id, body);
    }

    @DeleteMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        documents.softDelete(auth, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/documents/{id}/link-ticket")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentResponse linkTicket(@PathVariable UUID id,
                                       @RequestBody LinkTicketRequest body,
                                       Authentication auth) {
        return documents.linkTicket(auth, id, body);
    }

    // ── revisions ────────────────────────────────────────────────────────
    @GetMapping("/api/documents/{id}/revisions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public List<RevisionResponse> listRevisions(@PathVariable UUID id, Authentication auth) {
        return revisions.list(auth, id);
    }

    @PostMapping("/api/documents/{id}/revisions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<RevisionResponse> uploadRevision(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changeNote", required = false) String changeNote,
            Authentication auth) {
        RevisionResponse created = revisions.uploadNewRevision(auth, id, file, changeNote);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/api/documents/{id}/revisions/{revId}/download")
    @PreAuthorize("hasAnyAuthority('PERM_DOCUMENT_READ','PERM_DOCUMENT_DOWNLOAD')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @PathVariable UUID revId,
                                             Authentication auth) {
        DownloadFile file = revisions.download(auth, id, revId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(file.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(file.resource());
    }
}
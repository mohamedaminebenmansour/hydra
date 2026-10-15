package tn.hydra.document.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.audit.EventLogService;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.DocumentRevision;
import tn.hydra.document.dto.DocumentMapper;
import tn.hydra.document.dto.RevisionResponse;
import tn.hydra.document.repository.DocumentRepository;
import tn.hydra.document.repository.DocumentRevisionRepository;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class RevisionService {

    private final DocumentRepository documents;
    private final DocumentRevisionRepository revisions;
    private final AppUserRepository users;
    private final DocumentMapper mapper;
    private final FileStorage storage;
    private final FilenameBuilder filenameBuilder;
    private final EventLogService audit;
    private final DocumentService documentService;
    private final tn.hydra.security.FolderAccessService folderAccess;

    public RevisionService(DocumentRepository documents, DocumentRevisionRepository revisions,
                           AppUserRepository users, DocumentMapper mapper, FileStorage storage,
                           FilenameBuilder filenameBuilder, EventLogService audit,
                           DocumentService documentService,
                           tn.hydra.security.FolderAccessService folderAccess) {
        this.documents = documents; this.revisions = revisions; this.users = users;
        this.mapper = mapper; this.storage = storage; this.filenameBuilder = filenameBuilder; this.audit = audit;
        this.documentService = documentService; this.folderAccess = folderAccess;
    }

    public List<RevisionResponse> list(Authentication auth, UUID documentId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Document doc = assertReadable(documentId, me.getOrgId());
        folderAccess.assertAccess(me.getId(), doc.getFolder().getId(),
                tn.hydra.security.FolderAccessService.FolderAction.READ);
        return revisions.findByDocument_IdOrderByCreatedAtAsc(documentId).stream()
                .map(mapper::toRevisionResponse).toList();
    }

    @Transactional
    public RevisionResponse uploadNewRevision(Authentication auth, UUID documentId,
                                              MultipartFile file, String changeNote, String revision) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Document doc = documents.findByIdAndOrgId(documentId, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
        folderAccess.assertAccess(me.getId(), doc.getFolder().getId(),
                tn.hydra.security.FolderAccessService.FolderAction.UPLOAD);
        documentService.assertCanUpload(auth, me, doc.getFolder().getProject().getId());
        if (file == null || file.isEmpty())
            throw new BadRequestException("FILE_REQUIRED", "Un fichier est requis");

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("FILE_UNREADABLE", "Fichier illisible");
        }

        String revLabel = resolveRevLabel(documentId, revision);
        DocFolder folder = doc.getFolder();
        String projectCode = folder.getProject().getCode();
        String fileName = filenameBuilder.build(projectCode, doc.getDocType(),
                doc.getTitle(), revLabel, file.getOriginalFilename());
        String key = "org/" + me.getOrgId() + "/project/" + folder.getProject().getId()
                + "/" + folder.getCode() + "/doc/" + doc.getId() + "/"
                + revLabel.replace(' ', '_') + "__" + fileName;

        StoredFile stored = storage.save(key, bytes, file.getContentType());

        DocumentRevision rev = new DocumentRevision();
        rev.setRevLabel(revLabel);
        rev.setFileName(fileName);
        rev.setStorageKey(stored.key());
        rev.setMimeType(file.getContentType());
        rev.setSizeBytes(stored.size());
        rev.setChecksumSha256(stored.checksumSha256());
        rev.setChangeNote(changeNote);
        doc.addRevision(rev); // also updates document.currentRev

        documents.save(doc); // cascade persists the new revision
        audit.log("DOCUMENT_REVISION_UPLOADED", "document", doc.getId(),
                "{\"rev\":\"" + revLabel + "\"}");
        return mapper.toRevisionResponse(rev);
    }

    public DownloadFile download(Authentication auth, UUID documentId, UUID revisionId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Document doc = assertReadable(documentId, me.getOrgId());
        folderAccess.assertAccess(me.getId(), doc.getFolder().getId(),
                tn.hydra.security.FolderAccessService.FolderAction.DOWNLOAD);
        DocumentRevision rev = revisions.findById(revisionId)
                .filter(r -> r.getDocument().getId().equals(documentId))
                .orElseThrow(() -> new ResourceNotFoundException("REVISION_NOT_FOUND", "RÃƒÂ©vision introuvable"));
        return new DownloadFile(storage.loadAsResource(rev.getStorageKey()),
                rev.getFileName(), rev.getMimeType());
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬ helpers Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    private Document assertReadable(UUID documentId, UUID orgId) {
        return documents.findByIdAndOrgId(documentId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("DOCUMENT_NOT_FOUND", "Document introuvable"));
    }

    /** Explicit revision if provided (validated unique), else auto-bumped. */
    private String resolveRevLabel(UUID documentId, String revision) {
        if (revision != null && !revision.isBlank()) {
            String label = revision.trim();
            if (revisions.findByDocument_IdAndRevLabel(documentId, label).isPresent())
                throw new BadRequestException("REV_EXISTS", "Cette révision existe déjà");
            return label;
        }
        return nextRevLabel(documentId);
    }

    /** Rev.0 -> Rev.A -> Rev.B ... ; first revision is Rev.0. */
    private String nextRevLabel(UUID documentId) {
        return revisions.findTopByDocument_IdOrderByCreatedAtDesc(documentId)
                .map(r -> bump(r.getRevLabel()))
                .orElse("Rev.0");
    }

    private String bump(String label) {
        String cur = (label == null || label.isBlank()) ? "Rev.0" : label.trim();
        if (cur.equalsIgnoreCase("Rev.0")) return "Rev.A";
        char last = cur.charAt(cur.length() - 1);
        if (Character.isLetter(last)) {
            char upper = Character.toUpperCase(last);
            if (upper == 'Z') throw new BadRequestException("REV_LIMIT", "Limite de rÃƒÂ©vision atteinte");
            return "Rev." + (char) (upper + 1);
        }
        return "Rev.A";
    }
}
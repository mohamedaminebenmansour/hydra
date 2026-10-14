package tn.hydra.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.domain.Project;
import tn.hydra.document.dto.CreateDocumentRequest;
import tn.hydra.document.dto.DocumentMapper;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.repository.DocFolderRepository;
import tn.hydra.document.repository.DocumentRepository;
import tn.hydra.document.repository.TagRepository;
import tn.hydra.document.service.DocumentService;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.ticket.TicketRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID FOLDER = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private DocumentRepository documents;
    private DocFolderRepository folders;
    private TagRepository tags;
    private TicketRepository ticketRepo;
    private AppUserRepository users;
    private DocumentMapper mapper;
    private EventLogService audit;
    private DocumentService service;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        folders = mock(DocFolderRepository.class);
        tags = mock(TagRepository.class);
        ticketRepo = mock(TicketRepository.class);
        users = mock(AppUserRepository.class);
        mapper = mock(DocumentMapper.class);
        audit = mock(EventLogService.class);
        service = new DocumentService(documents, folders, tags, ticketRepo, users, mapper, audit);
        auth = new UsernamePasswordAuthenticationToken("u@hydra.tn", "pw");

        AppUser me = new AppUser();
        me.setId(UUID.randomUUID());
        me.setOrgId(ORG);
        me.setEmail("u@hydra.tn");
        when(users.findByEmail("u@hydra.tn")).thenReturn(Optional.of(me));

        when(mapper.toResponse(any(Document.class))).thenReturn(
                new DocumentResponse(DOC, UUID.randomUUID(), FOLDER, null, "Doc",
                        DocType.APD, null, DocStatus.DRAFT, java.util.List.of(), 0,
                        me.getId(), Instant.now(), null));
    }

    private DocFolder folderInOrg() {
        DocFolder f = new DocFolder();
        f.setId(FOLDER);
        Project p = new Project();
        p.setId(UUID.randomUUID());
        p.setOrgId(ORG);
        f.setProject(p);
        return f;
    }

    @Test
    void create_savesDraftDocument_inCallerOrg() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.of(folderInOrg()));

        service.create(auth, FOLDER, new CreateDocumentRequest("  Rapport  ", DocType.APD, null, null));

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(cap.capture());
        Document saved = cap.getValue();
        assertThat(saved.getOrgId()).isEqualTo(ORG);
        assertThat(saved.getTitle()).isEqualTo("Rapport");
        assertThat(saved.getStatus()).isEqualTo(DocStatus.DRAFT);
        verify(audit).log(eq("DOCUMENT_CREATED"), eq("document"), any(), any());
    }

    @Test
    void create_rejectsFolderOutsideOrg() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(auth, FOLDER,
                new CreateDocumentRequest("Doc", DocType.APD, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(documents, never()).save(any());
    }

    @Test
    void softDelete_marksArchivedAndSetsDeletedAt() {
        Document doc = new Document();
        doc.setId(DOC);
        doc.setOrgId(ORG);
        doc.setStatus(DocStatus.DRAFT);
        when(documents.findByIdAndOrgId(DOC, ORG)).thenReturn(Optional.of(doc));

        service.softDelete(auth, DOC);

        assertThat(doc.getDeletedAt()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocStatus.ARCHIVED);
        verify(documents).save(doc);
        verify(audit).log(eq("DOCUMENT_DELETED"), eq("document"), eq(DOC), isNull());
    }
}
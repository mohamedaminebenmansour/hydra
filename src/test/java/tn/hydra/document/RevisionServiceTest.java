package tn.hydra.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.document.domain.DocFolder;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.DocumentRevision;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.domain.Project;
import tn.hydra.document.dto.DocumentMapper;
import tn.hydra.document.dto.RevisionResponse;
import tn.hydra.document.repository.DocumentRepository;
import tn.hydra.document.repository.DocumentRevisionRepository;
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.FilenameBuilder;
import tn.hydra.document.service.RevisionService;
import tn.hydra.document.service.StoredFile;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RevisionServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentRevisionRepository revisions;
    private AppUserRepository users;
    private DocumentMapper mapper;
    private FileStorage storage;
    private FilenameBuilder filenameBuilder;
    private EventLogService audit;
    private RevisionService service;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        revisions = mock(DocumentRevisionRepository.class);
        users = mock(AppUserRepository.class);
        mapper = mock(DocumentMapper.class);
        storage = mock(FileStorage.class);
        filenameBuilder = new FilenameBuilder();
        audit = mock(EventLogService.class);
        service = new RevisionService(documents, revisions, users, mapper, storage, filenameBuilder,
                audit, mock(tn.hydra.document.service.DocumentService.class),
                mock(tn.hydra.security.FolderAccessService.class));
        auth = new UsernamePasswordAuthenticationToken("u@hydra.tn", "pw");

        AppUser me = new AppUser();
        me.setId(UUID.randomUUID());
        me.setOrgId(ORG);
        me.setEmail("u@hydra.tn");
        when(users.findByEmail("u@hydra.tn")).thenReturn(Optional.of(me));

        when(storage.save(anyString(), any(), any()))
                .thenAnswer(inv -> new StoredFile(inv.getArgument(0), 3, "deadbeef"));
        when(mapper.toRevisionResponse(any(DocumentRevision.class)))
                .thenReturn(new RevisionResponse(UUID.randomUUID(), "Rev.X", "f", "application/pdf",
                        3L, null, me.getId(), Instant.now()));
    }

    private Document doc() {
        Document d = new Document();
        d.setId(DOC);
        d.setOrgId(ORG);
        d.setDocType(DocType.APD);
        d.setTitle("Reservoir Nord");
        DocFolder f = new DocFolder();
        f.setCode("02_TECH");
        Project p = new Project();
        p.setId(UUID.randomUUID());
        p.setCode("SONEDE-01");
        f.setProject(p);
        d.setFolder(f);
        return d;
    }

    @Test
    void firstRevision_isRev0_andUpdatesCurrentRev() {
        when(documents.findByIdAndOrgId(DOC, ORG)).thenReturn(Optional.of(doc()));
        when(revisions.findTopByDocument_IdOrderByCreatedAtDesc(DOC)).thenReturn(Optional.empty());

        service.uploadNewRevision(auth, DOC,
                new MockMultipartFile("file", "a.pdf", "application/pdf", "abc".getBytes()), null, null);

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(cap.capture());
        assertThat(cap.getValue().getCurrentRev()).isEqualTo("Rev.0");
        assertThat(cap.getValue().getRevisions()).hasSize(1);
        verify(audit).log(eq("DOCUMENT_REVISION_UPLOADED"), eq("document"), eq(DOC), any());
    }

    @Test
    void nextRevision_bumpsRevA_afterRev0() {
        when(documents.findByIdAndOrgId(DOC, ORG)).thenReturn(Optional.of(doc()));
        DocumentRevision last = new DocumentRevision();
        last.setRevLabel("Rev.0");
        when(revisions.findTopByDocument_IdOrderByCreatedAtDesc(DOC)).thenReturn(Optional.of(last));

        service.uploadNewRevision(auth, DOC,
                new MockMultipartFile("file", "b.pdf", "application/pdf", "abc".getBytes()), "suite APD", null);

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(cap.capture());
        assertThat(cap.getValue().getCurrentRev()).isEqualTo("Rev.A");
    }
}
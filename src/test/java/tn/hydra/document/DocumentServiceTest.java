package tn.hydra.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
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
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.FilenameBuilder;
import tn.hydra.document.service.ReferenceGenerator;
import tn.hydra.document.service.StoredFile;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.ticket.TicketRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID FOLDER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private DocumentRepository documents;
    private DocFolderRepository folders;
    private TagRepository tags;
    private TicketRepository ticketRepo;
    private AppUserRepository users;
    private DocumentMapper mapper;
    private EventLogService audit;
    private ReferenceGenerator references;
    private FileStorage storage;
    private FilenameBuilder filenameBuilder;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private tn.hydra.security.FolderAccessService folderAccess;
    private jakarta.persistence.EntityManager em;
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
        references = mock(ReferenceGenerator.class);
        storage = mock(FileStorage.class);
        filenameBuilder = new FilenameBuilder();
        folderAccess = mock(tn.hydra.security.FolderAccessService.class);
        jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        // per-project upload gate: project_member says can_upload = true
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(), any())).thenReturn(true);
        service = new DocumentService(documents, folders, tags, ticketRepo, users, mapper, audit,
                references, storage, filenameBuilder, jdbc, folderAccess);
        em = mock(jakarta.persistence.EntityManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "em", em);
        auth = new UsernamePasswordAuthenticationToken("u@hydra.tn", "pw");

        AppUser me = new AppUser();
        me.setId(UUID.randomUUID());
        me.setOrgId(ORG);
        me.setEmail("u@hydra.tn");
        when(users.findByEmail("u@hydra.tn")).thenReturn(Optional.of(me));

        when(references.next(anyString())).thenReturn("SONEDE-01-00001");
        when(storage.save(anyString(), any(), any())).thenReturn(new StoredFile("k", 3, "abc"));
        when(mapper.toResponse(any(Document.class))).thenReturn(
                new DocumentResponse(DOC, "SONEDE-01-00001", PROJECT, FOLDER, null, "Doc", "d",
                        DocType.APD, "Rev.0", DocStatus.DRAFT, List.of(), 1, me.getId(),
                        Instant.now(), null));
    }

    private DocFolder folderInOrg() {
        DocFolder f = new DocFolder();
        f.setId(FOLDER);
        f.setCode("02_TECH");
        Project p = new Project();
        p.setId(PROJECT);
        p.setOrgId(ORG);
        p.setCode("SONEDE-01");
        f.setProject(p);
        return f;
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", "abc".getBytes());
    }

    // ── create (metadata only) ───────────────────────────────────────────
    @Test
    void create_savesDraftDocument_andGeneratesReference() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.of(folderInOrg()));

        service.create(auth, FOLDER, new CreateDocumentRequest("  Rapport  ", "desc", DocType.APD, null, null));

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(cap.capture());
        Document saved = cap.getValue();
        assertThat(saved.getOrgId()).isEqualTo(ORG);
        assertThat(saved.getTitle()).isEqualTo("Rapport");
        assertThat(saved.getStatus()).isEqualTo(DocStatus.DRAFT);
        assertThat(saved.getReference()).isEqualTo("SONEDE-01-00001");
        verify(references).next("SONEDE-01");
    }

    @Test
    void create_rejectsFolderOutsideOrg() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(auth, FOLDER,
                new CreateDocumentRequest("Doc", null, DocType.APD, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(documents, never()).save(any());
    }

    // ── combined upload ──────────────────────────────────────────────────
    @Test
    void upload_createsDocumentWithReferenceAndFirstRevision() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.of(folderInOrg()));

        service.upload(auth, FOLDER, "Reservoir Nord", DocType.APD, null, null, null, "note", pdf());

        ArgumentCaptor<Document> cap = ArgumentCaptor.forClass(Document.class);
        verify(em).persist(cap.capture());
        Document saved = cap.getValue();
        assertThat(saved.getReference()).isEqualTo("SONEDE-01-00001");
        assertThat(saved.getDescription()).isEqualTo("note");
        assertThat(saved.getStatus()).isEqualTo(DocStatus.DRAFT);
        assertThat(saved.getCurrentRev()).isEqualTo("Rev.0");
        assertThat(saved.getRevisions()).hasSize(1);
        verify(storage).save(anyString(), any(), any());
        verify(audit).log(eq("DOCUMENT_UPLOADED"), eq("document"), any(), any());
    }

    @Test
    void upload_bypassesCanUploadForOwner() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.of(folderInOrg()));
        AppUser owner = new AppUser();
        owner.setId(UUID.randomUUID());
        owner.setOrgId(ORG);
        owner.setEmail("u@hydra.tn");
        owner.setIsOwner(true);
        when(users.findByEmail("u@hydra.tn")).thenReturn(Optional.of(owner));
        // project_member would deny (no row / can_upload=false) — owner must bypass anyway
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(), any())).thenReturn(false);

        service.upload(auth, FOLDER, "Reservoir Nord", DocType.APD, null, null, null, "note", pdf());

        verify(storage).save(anyString(), any(), any());
        verify(audit).log(eq("DOCUMENT_UPLOADED"), eq("document"), any(), any());
    }

    @Test
    void upload_requiresFile() {
        when(folders.findByIdAndProject_OrgId(FOLDER, ORG)).thenReturn(Optional.of(folderInOrg()));
        assertThatThrownBy(() -> service.upload(auth, FOLDER, "Doc", DocType.APD,
                null, null, null, null, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(tn.hydra.exception.BadRequestException.class);
        verify(em, never()).persist(any());
    }

    // ── register ─────────────────────────────────────────────────────────
    @Test
    void register_returnsProjectDocumentsOrderedByReference() {
        when(folderAccess.readableFolderIds(any(), eq(PROJECT))).thenReturn(null);
        when(documents.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.register(auth, PROJECT, Pageable.ofSize(20));

        assertThat(page.items()).isEmpty();
        verify(documents).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void search_asOwnerListsFolderDocuments() {
        when(folderAccess.readableFolderIds(any(), eq(PROJECT))).thenReturn(null);
        when(documents.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.search(auth, new tn.hydra.document.dto.DocumentSearchQuery(
                FOLDER, PROJECT, null, null, null, null, null, null, null, null),
                Pageable.ofSize(200));

        assertThat(page.items()).isEmpty();
        verify(documents).findAll(any(Specification.class), any(Pageable.class));
    }

    // ── soft delete ──────────────────────────────────────────────────────
    @Test
    void softDelete_marksArchivedAndSetsDeletedAt() {
        Document doc = new Document();
        doc.setId(DOC);
        doc.setOrgId(ORG);
        doc.setStatus(DocStatus.DRAFT);
        doc.setFolder(folderInOrg());
        when(documents.findByIdAndOrgId(DOC, ORG)).thenReturn(Optional.of(doc));

        service.softDelete(auth, DOC);

        assertThat(doc.getDeletedAt()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocStatus.ARCHIVED);
        verify(documents).save(doc);
    }
}
package tn.hydra.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private tn.hydra.security.FolderAccessService folderAccess;
    private EventLogService audit;
    private ProjectService service;
    private Authentication auth;

    private Map<String, Object> projectRow(UUID id, String name, String code, String status) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        row.put("code", code);
        row.put("status", status);
        row.put("created_at", Instant.now());
        return row;
    }

    private AppUser memberUser() {
        AppUser u = new AppUser();
        u.setId(MEMBER_ID);
        u.setOrgId(ORG);
        u.setEmail("member@hydra.tn");
        u.setIsActive(true);
        return u;
    }

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new ProjectService(jdbc, users, audit,
                folderAccess = mock(tn.hydra.security.FolderAccessService.class));
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");

        AppUser admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setOrgId(ORG);
        admin.setEmail("admin@hydra.tn");
        admin.setIsOwner(true); // owner → companyWide access, keeps single-arg queries
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin));
    }

    private ProjectService.CreateProjectRequest req(String name, String code) {
        return new ProjectService.CreateProjectRequest(name, code, null, null, null,
                null, null, null, null, null, null, null);
    }

    // ── list ────────────────────────────────────────────────────────────

    @Test
    void list_isScopedToCallerOrg() {
        when(jdbc.queryForList(anyString(), eq(ORG))).thenReturn(List.of(
                projectRow(PROJECT_ID, "Chantier Nord", "CN", "ACTIF")));

        List<ProjectService.ProjectResponse> list = service.list(auth);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("Chantier Nord");
        assertThat(list.get(0).code()).isEqualTo("CN");
        assertThat(list.get(0).status()).isEqualTo("ACTIF");
        verify(jdbc).queryForList(contains("order by p.name"), eq(ORG));
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    void create_requiresName() {
        assertThatThrownBy(() -> service.create(auth, req("  ", "CN")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("obligatoire");
        verifyNoInteractions(audit);
    }

    @Test
    void create_insertsActifProject_andAudits() {
        stubCreate();
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", "CN", "ACTIF")));

        ProjectService.ProjectResponse created =
                service.create(auth, req(" Chantier Nord ", "CN"));

        assertThat(created.name()).isEqualTo("Chantier Nord");
        assertThat(created.status()).isEqualTo("ACTIF");
        verify(jdbc).queryForObject(contains("insert into project"), eq(UUID.class),
                eq(ORG), eq("CN"), eq("Chantier Nord"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(auditDetails("PROJECT_CREATED")).contains("Chantier Nord");
    }

    @Test
    void create_convertsBlankCodeToNull() {
        stubCreate();
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", null, "ACTIF")));

        service.create(auth, req("Chantier Nord", "   "));

        verify(jdbc).queryForObject(contains("insert into project"), eq(UUID.class),
                eq(ORG), isNull(), eq("Chantier Nord"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── update ──────────────────────────────────────────────────────────

    private ProjectService.UpdateProjectRequest upd(String name, String code, String status) {
        return new ProjectService.UpdateProjectRequest(name, code, status,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void update_rejectsInvalidStatus() {
        assertThatThrownBy(() -> service.update(auth, PROJECT_ID, upd("Chantier", "CN", "ARCHIVED")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Statut");
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void update_rejectsProjectOfAnotherOrg() {
        when(jdbc.queryForList("select id from project where id = ? and org_id = ?",
                UUID.class, PROJECT_ID, ORG)).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(auth, PROJECT_ID, upd("Chantier", "CN", "CLOTURE")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("introuvable");
        verify(jdbc, never()).update(contains("update project set"), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void update_closesProject_andAudits() {
        var found = new HashMap<String, Object>();
        found.put("id", PROJECT_ID);
        doReturn(List.of(found)).when(jdbc).queryForList(
                "select id from project where id = ? and org_id = ?", UUID.class, PROJECT_ID, ORG);
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", "CN", "CLOTURE")));

        ProjectService.ProjectResponse updated =
                service.update(auth, PROJECT_ID, upd("Chantier Nord", "CN", "CLOTURE"));

        assertThat(updated.status()).isEqualTo("CLOTURE");
        verify(jdbc).update(contains("update project set"),
                eq("Chantier Nord"), eq("CN"), eq("CLOTURE"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("TND"), eq(PROJECT_ID));
        assertThat(auditDetails("PROJECT_UPDATED")).contains("CLOTURE");
    }

    // ── get ─────────────────────────────────────────────────────────────

    @Test
    void get_returnsNullForForeignProject() {
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG))).thenReturn(List.of());
        assertThat(service.get(auth, PROJECT_ID)).isNull();
    }

    // ── folder fallback / repair ─────────────────────────────────────────

    @Test
    void create_insertsSevenDefaultFolders_whenNoTemplates() {
        stubCreate();
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", "CN", "ACTIF")));

        service.create(auth, req("Chantier Nord", "CN"));

        // hard fallback: 7 doc_folder inserts even though the template table is empty
        verify(jdbc, times(7)).update(contains("insert into doc_folder"),
                eq(PROJECT_ID), any(), any(), anyInt());
    }

    @Test
    void listFolders_mapsCreatedAt_andOrdersBySort() {
        var f = new HashMap<String, Object>();
        f.put("id", UUID.randomUUID());
        f.put("project_id", PROJECT_ID);
        f.put("parent_id", null);
        f.put("code", "01_ADMIN");
        f.put("name", "Administratif");
        f.put("sort_order", 1);
        Instant created = Instant.now();
        f.put("created_at", created);
        when(jdbc.queryForList(contains("select id from project"), eq(UUID.class), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(PROJECT_ID));
        when(jdbc.queryForList(contains("from doc_folder"), eq(PROJECT_ID)))
                .thenReturn(List.of(f));
        when(folderAccess.readableFolderIds(any(), eq(PROJECT_ID))).thenReturn(null); // no ACL filter

        List<ProjectService.FolderResponse> folders = service.listFolders(auth, PROJECT_ID);

        assertThat(folders).hasSize(1);
        assertThat(folders.get(0).code()).isEqualTo("01_ADMIN");
        assertThat(folders.get(0).sortOrder()).isEqualTo(1);
        assertThat(folders.get(0).createdAt()).isEqualTo(created);
    }

    // ── create with members ──────────────────────────────────────────────

    @Test
    void create_withMemberUserIds_createsMembershipsAndAuditsEach() {
        stubCreate();
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(memberUser()));
        when(jdbc.queryForList(contains("where p.id = ? and p.org_id = ?"),
                eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", "CN", "ACTIF")));

        service.create(auth, new ProjectService.CreateProjectRequest(
                "Chantier Nord", "CN", null, null, null, null, null,
                null, null, null, null, List.of(MEMBER_ID)));

        verify(jdbc).update(contains("insert into project_member"),
                eq(PROJECT_ID), eq(MEMBER_ID), any());
        verify(audit).log(eq("PROJECT_MEMBER_ADDED"), eq("project"), eq(PROJECT_ID),
                contains(MEMBER_ID.toString()));
    }

    @Test
    void create_invalidMemberUserId_throwsBadRequest() {
        stubCreate();
        when(users.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(auth, new ProjectService.CreateProjectRequest(
                "Chantier Nord", "CN", null, null, null, null, null,
                null, null, null, null, List.of(MEMBER_ID))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "USER_NOT_FOUND");
    }

    // ── add member ───────────────────────────────────────────────────────

    @Test
    void addMember_defaultsRoleToEngineer_whenMissing() {
        when(jdbc.queryForList(contains("select id from project where id = ? and org_id = ?"),
                eq(UUID.class), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(PROJECT_ID));
        when(jdbc.queryForList(contains("select 1 from project_member"), eq(PROJECT_ID), any()))
                .thenReturn(List.of());
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(memberUser()));
        var memberRow = new HashMap<String, Object>();
        memberRow.put("user_id", MEMBER_ID);
        memberRow.put("full_name", "Member User");
        memberRow.put("email", "member@hydra.tn");
        memberRow.put("project_role", "ENGINEER");
        memberRow.put("can_upload", true);
        memberRow.put("created_at", Instant.now());
        when(jdbc.queryForList(contains("pm.user_id"), eq(PROJECT_ID), eq(MEMBER_ID)))
                .thenReturn(List.of(memberRow));

        service.addMember(auth, PROJECT_ID, MEMBER_ID, null, true);

        verify(jdbc).update(contains("insert into project_member"),
                eq(PROJECT_ID), eq(MEMBER_ID), eq("ENGINEER"), anyBoolean(), any());
        verify(audit).log(eq("PROJECT_MEMBER_ADDED"), eq("project"), eq(PROJECT_ID),
                contains("ENGINEER"));
    }

    @Test
    void addMember_duplicateMember_throwsMemberExists() {
        when(jdbc.queryForList(contains("select id from project where id = ? and org_id = ?"),
                eq(UUID.class), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(PROJECT_ID));
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(memberUser()));
        when(jdbc.queryForList(contains("select 1 from project_member"), eq(PROJECT_ID), any()))
                .thenReturn(List.of(Map.of("1", 1)));

        assertThatThrownBy(() -> service.addMember(auth, PROJECT_ID, MEMBER_ID, "ENGINEER", true))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "MEMBER_EXISTS");
    }

    @Test
    void addMember_invalidUserId_throwsBadRequest() {
        when(jdbc.queryForList(contains("select id from project where id = ? and org_id = ?"),
                eq(UUID.class), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(PROJECT_ID));
        when(users.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(auth, PROJECT_ID, MEMBER_ID, "ENGINEER", true))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "USER_NOT_FOUND");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void stubCreate() {
        when(jdbc.queryForObject(contains("insert into project"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PROJECT_ID);
        // no folder templates (org) and none global → no folder inserts
        when(jdbc.queryForList(contains("from doc_folder_template"), eq(ORG)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("where org_id is null")))
                .thenReturn(List.of());
        // get() auto-repair counts folders; create's own folders are visible, so it won't re-clone
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), any()))
                .thenReturn(7);
    }

    private String auditDetails(String action) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq(action), eq("project"), any(), captor.capture());
        return captor.getValue();
    }
}

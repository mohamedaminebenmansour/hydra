package tn.hydra.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
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

    private JdbcTemplate jdbc;
    private AppUserRepository users;
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

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new ProjectService(jdbc, users, audit);
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");

        AppUser admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setOrgId(ORG);
        admin.setEmail("admin@hydra.tn");
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin));
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
        verify(jdbc).queryForList(contains("order by name"), eq(ORG));
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    void create_requiresName() {
        assertThatThrownBy(() -> service.create(auth, "  ", "CN"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("obligatoire");
        verifyNoInteractions(audit);
    }

    @Test
    void create_insertsActifProject_andAudits() {
        when(jdbc.queryForObject(contains("insert into project"), eq(UUID.class),
                any(), any(), any(), any())).thenReturn(PROJECT_ID);
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", "CN", "ACTIF")));

        ProjectService.ProjectResponse created = service.create(auth, " Chantier Nord ", "CN");

        assertThat(created.name()).isEqualTo("Chantier Nord");
        assertThat(created.status()).isEqualTo("ACTIF");
        verify(jdbc).queryForObject(contains("'ACTIF'"), eq(UUID.class),
                eq(ORG), eq("CN"), eq("Chantier Nord"), any());
        assertThat(auditDetails("PROJECT_CREATED")).contains("Chantier Nord");
    }

    @Test
    void create_convertsBlankCodeToNull() {
        when(jdbc.queryForObject(contains("insert into project"), eq(UUID.class),
                any(), any(), any(), any())).thenReturn(PROJECT_ID);
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Nord", null, "ACTIF")));

        service.create(auth, "Chantier Nord", "   ");

        verify(jdbc).queryForObject(contains("insert into project"), eq(UUID.class),
                eq(ORG), isNull(), eq("Chantier Nord"), any());
    }

    // ── update ──────────────────────────────────────────────────────────

    @Test
    void update_rejectsInvalidStatus() {
        assertThatThrownBy(() -> service.update(auth, PROJECT_ID, "Chantier", "CN", "ARCHIVED"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Statut");
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void update_rejectsProjectOfAnotherOrg() {
        when(jdbc.queryForList("select id from project where id = ? and org_id = ?",
                UUID.class, PROJECT_ID, ORG)).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(auth, PROJECT_ID, "Chantier", "CN", "CLOTURE"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("introuvable");
        verify(jdbc, never()).update(contains("update project"), any(), any(), any(), any());
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
                service.update(auth, PROJECT_ID, "Chantier Nord", "CN", "CLOTURE");

        assertThat(updated.status()).isEqualTo("CLOTURE");
        verify(jdbc).update(contains("update project set name = ?"),
                eq("Chantier Nord"), eq("CN"), eq("CLOTURE"), eq(PROJECT_ID));
        assertThat(auditDetails("PROJECT_UPDATED")).contains("CLOTURE");
    }

    // ── get ─────────────────────────────────────────────────────────────

    @Test
    void get_returnsNullForForeignProject() {
        when(jdbc.queryForList(anyString(), eq(PROJECT_ID), eq(ORG))).thenReturn(List.of());
        assertThat(service.get(auth, PROJECT_ID)).isNull();
    }

    private String auditDetails(String action) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq(action), eq("project"), any(), captor.capture());
        return captor.getValue();
    }
}


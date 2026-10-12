package tn.hydra.role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ROLE_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private EventLogService audit;
    private RoleService service;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new RoleService(jdbc, users, audit);
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");

        AppUser admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setOrgId(ORG);
        admin.setEmail("admin@hydra.tn");
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin));
    }

    // ── list ────────────────────────────────────────────────────────────

    @Test
    void listRoles_groupsPermissionsUnderEachRole_andFlagsSystemRoles() {
        UUID systemRoleId = UUID.randomUUID();
        var row1 = new HashMap<String, Object>();
        row1.put("id", systemRoleId);
        row1.put("code", "OWNER");
        row1.put("label", "Gérant");
        row1.put("org_id", null);
        row1.put("permission_id", "USER_CREATE");
        var row2 = new HashMap<String, Object>(row1);
        row2.put("permission_id", "ROLE_MANAGE");
        var row3 = new HashMap<String, Object>();
        row3.put("id", ROLE_ID);
        row3.put("code", "QA_LEAD");
        row3.put("label", "Chef qualité");
        row3.put("org_id", ORG);
        row3.put("permission_id", null);
        when(jdbc.queryForList(anyString(), eq(ORG))).thenReturn(List.of(row1, row2, row3));

        List<RoleService.RoleResponse> list = service.listRoles(auth);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).code()).isEqualTo("OWNER");
        assertThat(list.get(0).isSystem()).isTrue();
        assertThat(list.get(0).permissions()).containsExactly("USER_CREATE", "ROLE_MANAGE");
        assertThat(list.get(1).code()).isEqualTo("QA_LEAD");
        assertThat(list.get(1).isSystem()).isFalse();
        assertThat(list.get(1).permissions()).isEmpty();
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    void createRole_slugsTheLabel_insertsWithScopeAll_andAudits() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("QA_LEAD"), eq(ORG)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(ORG), eq("QA_LEAD"), eq("QA lead")))
                .thenReturn(ROLE_ID);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("TICKET_READ")))
                .thenReturn(List.of("TICKET_READ"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("NOPE")))
                .thenReturn(List.of());

        RoleService.RoleResponse res =
                service.createRole(auth, "QA lead", List.of("TICKET_READ", "NOPE"));

        verify(jdbc).queryForObject(contains("insert into role"),
                eq(UUID.class), eq(ORG), eq("QA_LEAD"), eq("QA lead"));
        verify(jdbc).update(contains("insert into role_permission"), eq(ROLE_ID), eq("TICKET_READ"));
        verify(audit).log(eq("ROLE_CREATED"), eq("role"), eq(ROLE_ID), any());
        assertThat(res.id()).isEqualTo(ROLE_ID);
        assertThat(res.code()).isEqualTo("QA_LEAD");
        assertThat(res.label()).isEqualTo("QA lead");
        assertThat(res.isSystem()).isFalse();
        assertThat(res.permissions()).containsExactly("TICKET_READ");
    }

    @Test
    void createRole_rejectsCodeAlreadyUsedByAnyOrg() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("QA_LEAD"), eq(ORG)))
                .thenReturn(List.of(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> service.createRole(auth, "QA lead", List.of()))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CODE_EXISTS"));
        verify(jdbc, never()).queryForObject(anyString(), eq(UUID.class), any(), any(), any());
    }

    @Test
    void createRole_rejectsBlankLabel() {
        assertThatThrownBy(() -> service.createRole(auth, "  ", List.of()))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REQUIRED_FIELDS"));
    }

    // ── update ──────────────────────────────────────────────────────────

    @Test
    void updateRole_rejectsSystemRole() {
        when(jdbc.queryForList(anyString(), eq(ROLE_ID))).thenReturn(
                List.of(map("org_id", null, "code", "OWNER")));

        assertThatThrownBy(() -> service.updateRole(auth, ROLE_ID, "Nouveau", List.of()))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SYSTEM_ROLE"));
        verify(jdbc, never()).update(contains("update role"), any(), any());
    }

    @Test
    void updateRole_rejectsRoleFromAnotherOrg() {
        when(jdbc.queryForList(anyString(), eq(ROLE_ID))).thenReturn(
                List.of(map("org_id", UUID.randomUUID(), "code", "QA_LEAD")));

        assertThatThrownBy(() -> service.updateRole(auth, ROLE_ID, "Nouveau", List.of()))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ROLE_NOT_FOUND"));
    }

    @Test
    void updateRole_replacesAllPermissions_andAudits() {
        when(jdbc.queryForList(anyString(), eq(ROLE_ID))).thenReturn(
                List.of(map("org_id", ORG, "code", "QA_LEAD")));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("DOCUMENT_READ")))
                .thenReturn(List.of("DOCUMENT_READ"));

        RoleService.RoleResponse res =
                service.updateRole(auth, ROLE_ID, "Chef qualité senior", List.of("DOCUMENT_READ"));

        verify(jdbc).update(contains("update role set label"), eq("Chef qualité senior"), eq(ROLE_ID));
        verify(jdbc).update(contains("delete from role_permission"), eq(ROLE_ID));
        verify(jdbc).update(contains("insert into role_permission"), eq(ROLE_ID), eq("DOCUMENT_READ"));
        verify(audit).log(eq("ROLE_UPDATED"), eq("role"), eq(ROLE_ID), any());
        assertThat(res.label()).isEqualTo("Chef qualité senior");
        assertThat(res.permissions()).containsExactly("DOCUMENT_READ");
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}

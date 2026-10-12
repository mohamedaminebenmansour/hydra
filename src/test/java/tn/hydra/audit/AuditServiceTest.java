package tn.hydra.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private AuditService service;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        service = new AuditService(jdbc, users);
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");

        AppUser admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setOrgId(ORG);
        admin.setEmail("admin@hydra.tn");
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin));
    }

    // ── activity feed ───────────────────────────────────────────────────

    @Test
    void events_alwaysScopesToCallerOrg_andDefaultsPageAndSize() {
        when(jdbc.queryForList(anyString(), eq(ORG))).thenReturn(List.of());

        service.events(auth, null, null, null, null, null, null);

        // no user/action/date clauses, limit 50, offset 0
        verify(jdbc).queryForList(contains("e.org_id = ?"), eq(ORG), eq(50), eq(0));
    }

    @Test
    void events_appliesEveryFilter_andMapsRows() {
        Instant createdAt = Instant.parse("2026-10-01T09:30:00Z");
        var row = new HashMap<String, Object>();
        row.put("id", 42L);
        row.put("created_at", createdAt);
        row.put("user_id", TARGET);
        row.put("email", "member@hydra.tn");
        row.put("full_name", "Membre");
        row.put("action", "USER_CREATED");
        row.put("entity_type", "app_user");
        row.put("entity_id", TARGET);
        row.put("details", "{\"email\":\"member@hydra.tn\"}");
        when(jdbc.queryForList(anyString(), eq(ORG), eq(TARGET), eq("USER_CREATED"),
                eq(Instant.parse("2026-10-01T00:00:00Z")),
                eq(Instant.parse("2026-10-03T00:00:00Z")), eq(10), eq(20)))
                .thenReturn(List.of(row));

        List<AuditService.EventResponse> events = service.events(auth, TARGET,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
                "USER_CREATED", 2, 10);

        assertThat(events).hasSize(1);
        AuditService.EventResponse e = events.get(0);
        assertThat(e.id()).isEqualTo(42L);
        assertThat(e.createdAt()).isEqualTo(createdAt);
        assertThat(e.userId()).isEqualTo(TARGET);
        assertThat(e.userEmail()).isEqualTo("member@hydra.tn");
        assertThat(e.userFullName()).isEqualTo("Membre");
        assertThat(e.action()).isEqualTo("USER_CREATED");
        assertThat(e.details()).isEqualTo("{\"email\":\"member@hydra.tn\"}");
    }

    @Test
    void events_capsSizeAt200() {
        when(jdbc.queryForList(anyString(), eq(ORG), eq(200), eq(0))).thenReturn(List.of());

        service.events(auth, null, null, null, null, null, 9999);

        verify(jdbc).queryForList(anyString(), eq(ORG), eq(200), eq(0));
    }

    // ── per-user summary ────────────────────────────────────────────────

    @Test
    void userSummary_rejectsUserFromAnotherOrg() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(TARGET)))
                .thenReturn(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.userSummary(auth, TARGET))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void userSummary_returnsLastLoginAndLastAction() {
        Instant lastLogin = Instant.parse("2026-10-01T08:00:00Z");
        Instant lastAction = Instant.parse("2026-10-02T17:45:00Z");
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(TARGET))).thenReturn(List.of(ORG));
        var row = new HashMap<String, Object>();
        row.put("last_login", lastLogin);
        row.put("last_action", lastAction);
        when(jdbc.queryForList(anyString(), eq(ORG), eq(TARGET))).thenReturn(List.of(row));

        AuditService.UserSummaryResponse summary = service.userSummary(auth, TARGET);

        assertThat(summary.lastLoginAt()).isEqualTo(lastLogin);
        assertThat(summary.lastActionAt()).isEqualTo(lastAction);
    }

    @Test
    void userSummary_returnsNullsWhenUserHasNoEvents() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(TARGET))).thenReturn(List.of(ORG));
        var row = new HashMap<String, Object>();
        row.put("last_login", null);
        row.put("last_action", null);
        when(jdbc.queryForList(anyString(), eq(ORG), eq(TARGET))).thenReturn(List.of(row));

        AuditService.UserSummaryResponse summary = service.userSummary(auth, TARGET);

        assertThat(summary.lastLoginAt()).isNull();
        assertThat(summary.lastActionAt()).isNull();
    }
}

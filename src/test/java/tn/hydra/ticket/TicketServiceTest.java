package tn.hydra.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tn.hydra.audit.EventLogService;
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

class TicketServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private EventLogService audit;
    private TicketService service;
    private AppUser me;
    private Authentication auth;
    private Authentication writer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new TicketService(jdbc, users, audit);

        me = new AppUser();
        me.setId(UUID.randomUUID());
        me.setOrgId(ORG);
        me.setEmail("me@hydra.tn");
        when(users.findByEmail("me@hydra.tn")).thenReturn(Optional.of(me));

        auth = new UsernamePasswordAuthenticationToken("me@hydra.tn", "pw");
        writer = new UsernamePasswordAuthenticationToken("me@hydra.tn", "pw",
                List.of(new SimpleGrantedAuthority("PERM_TICKET_WRITE")));
    }

    private Map<String, Object> ticketRow(String status, UUID assignedTo, UUID projectId) {
        var row = new HashMap<String, Object>();
        row.put("id", TICKET_ID);
        row.put("title", "Fuite");
        row.put("description", "regard");
        row.put("status", status);
        row.put("priority", "MEDIUM");
        row.put("project_id", projectId);
        row.put("project_name", "Chantier Nord");
        row.put("assigned_to", assignedTo);
        row.put("assigned_to_name", null);
        row.put("start_at", null);
        row.put("due_at", null);
        row.put("created_at", Instant.now());
        row.put("created_by", me.getId());
        row.put("created_by_name", "Moi");
        row.put("resolved_at", null);
        row.put("resolved_by", null);
        return row;
    }

    // ---- create ----

    @Test
    void create_requiresTitleAndProject() {
        assertThatThrownBy(() -> service.create(auth, " ", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("titre");
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("projet");
        verifyNoInteractions(audit);
    }

    @Test
    void create_rejectsForeignOrClosedProject() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Projet");

        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "CLOTURE")));
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clôturé");
    }

    @Test
    void create_insertsOuvertTicket_andAudits() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "ACTIF")));
        when(jdbc.queryForObject(contains("insert into ticket"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TICKET_ID);
        var createdRow = ticketRow("OUVERT", null, PROJECT_ID);
        createdRow.put("priority", "HIGH");
        when(jdbc.queryForList(contains("from ticket t"), eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(createdRow));

        TicketService.TicketResponse created = service.create(auth, "Fuite", "regard",
                PROJECT_ID, null, "HIGH", LocalDate.now(), LocalDate.now().plusDays(2), null);

        assertThat(created.status()).isEqualTo("OUVERT");
        assertThat(created.priority()).isEqualTo("HIGH");
        verify(jdbc).queryForObject(contains("'OUVERT'"), eq(UUID.class),
                eq(ORG), eq("Fuite"), eq("regard"), eq("HIGH"),
                eq(PROJECT_ID), isNull(), any(LocalDate.class), any(LocalDate.class), eq(me.getId()), isNull());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq("TICKET_CREATED"), eq("ticket"), eq(TICKET_ID), captor.capture());
        assertThat(captor.getValue()).contains("Fuite");
    }

    @Test
    void create_defaultsPriorityToMedium_andValidatesAssignee() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "ACTIF")));

        when(jdbc.queryForList("select id from app_user where id = ? and org_id = ?",
                UUID.class, OTHER_USER, ORG)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                OTHER_USER, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Utilisateur");

        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, "URGENT", null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Priorité");
    }

    // ---- list filters ----

    @Test
    void list_alwaysScopesToOrg_andAddsMineFilter() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ticketRow("OUVERT", null, PROJECT_ID)));

        service.list(auth, null, null, null, null, true);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("where t.org_id = ?"), captor.capture());
        assertThat(captor.getValue()[0]).isEqualTo(ORG);
        verify(jdbc).queryForList(contains("and t.assigned_to = ?"), any(Object[].class));
    }

    @Test
    void list_appliesStatusAndPriorityFilters() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.list(auth, null, null, "OUVERT", "HIGH", false);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("and t.status = ?"), captor.capture());
        assertThat(captor.getValue()).contains("OUVERT");
        assertThat(captor.getValue()).contains("HIGH");
    }

    // ---- update ----

    @Test
    void update_returnsNullForForeignTicket() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG))).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(auth, TICKET_ID, "Fuite", null,
                null, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ticket");
    }

    @Test
    void update_onlyChangesProvidedFields() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow("OUVERT", null, PROJECT_ID)));
        when(jdbc.queryForList(contains("from ticket t"), eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow("OUVERT", null, PROJECT_ID)));

        service.update(auth, TICKET_ID, "Nouveau titre", null,
                null, null, "LOW", null, null, null);

        verify(jdbc).update(contains("update ticket set updated_at = now(), title = ?, priority = ?"),
                eq("Nouveau titre"), eq("LOW"), eq(TICKET_ID), eq(ORG));
        verify(audit).log(eq("TICKET_UPDATED"), eq("ticket"), eq(TICKET_ID), isNull());
    }

    // ---- transitions ----

    private void stubTicket(String status, UUID assignedTo) {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow(status, assignedTo, PROJECT_ID)));
    }

    @Test
    void start_movesOuvertToEnCours_forAssigneeWithoutWritePerm() {
        stubTicket("OUVERT", me.getId());

        service.start(auth, TICKET_ID);

        verify(jdbc).update(contains("set status = ?, updated_at = now()"),
                eq("EN_COURS"), eq(TICKET_ID), eq(ORG));
        verify(audit).log(eq("TICKET_STATUS_CHANGED"), eq("ticket"), eq(TICKET_ID), contains("EN_COURS"));
    }

    @Test
    void start_rejectsNonAssigneeWithoutWritePerm() {
        stubTicket("OUVERT", OTHER_USER);

        assertThatThrownBy(() -> service.start(auth, TICKET_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("assigné");
        verify(jdbc, never()).update(contains("update ticket"), any(), any(), any(), any());
    }

    @Test
    void start_allowedForWriterEvenWhenAssignedToSomeoneElse() {
        stubTicket("OUVERT", OTHER_USER);

        service.start(writer, TICKET_ID);

        verify(jdbc).update(contains("set status = ?"), eq("EN_COURS"), eq(TICKET_ID), eq(ORG));
    }

    @Test
    void illegalTransitionsAreRejected() {
        stubTicket("RESOLU", me.getId());
        assertThatThrownBy(() -> service.start(auth, TICKET_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Transition");
        verify(jdbc, never()).update(contains("update ticket"), any(), any(), any(), any());
    }

    @Test
    void wait_andResume_toggleBetweenEnCoursAndEnAttente() {
        stubTicket("EN_COURS", me.getId());
        service.wait_(auth, TICKET_ID);
        verify(jdbc).update(contains("update ticket"), eq("EN_ATTENTE"), eq(TICKET_ID), eq(ORG));

        stubTicket("EN_ATTENTE", me.getId());
        service.resume(auth, TICKET_ID);
        verify(jdbc).update(contains("update ticket"), eq("EN_COURS"), eq(TICKET_ID), eq(ORG));
    }

    @Test
    void close_setsResolvedStampAndAuditsTICKET_CLOSED() {
        stubTicket("EN_ATTENTE", OTHER_USER);

        service.close(writer, TICKET_ID);

        verify(jdbc).update(contains("resolved_at = now(), resolved_by = ?"),
                eq("RESOLU"), eq(me.getId()), eq(TICKET_ID), eq(ORG));
        verify(audit).log(eq("TICKET_CLOSED"), eq("ticket"), eq(TICKET_ID), any());
    }

    @Test
    void transitions_rejectForeignTicket() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG))).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(auth, TICKET_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ticket");
        verifyNoInteractions(audit);
    }
}
package tn.hydra.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.security.SessionRevoker;
import tn.hydra.security.ToxicPairService;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private PasswordEncoder encoder;
    private EventLogService audit;
    private SessionRevoker sessions;
    private ToxicPairService toxicPairs;
    private UserService service;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        audit = mock(EventLogService.class);
        sessions = mock(SessionRevoker.class);
        toxicPairs = mock(ToxicPairService.class);
        service = new UserService(jdbc, users, encoder, audit, sessions, toxicPairs);
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin()));
    }

    private AppUser admin() {
        return user(ADMIN_ID, "admin@hydra.tn", true, true);
    }

    private AppUser user(UUID id, String email, boolean owner, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setOrgId(ORG);
        u.setEmail(email);
        u.setPasswordHash("$hash");
        u.setIsOwner(owner);
        u.setIsActive(active);
        return u;
    }

    // ── change my password ──────────────────────────────────────────────

    @Test
    void changeMyPassword_rejectsWrongOldPassword() {
        when(encoder.matches("wrong", "$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changeMyPassword(auth, "wrong", "newpassword1"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WRONG_PASSWORD");
                    assertThat(e.getMessage()).isEqualTo("Ancien mot de passe incorrect");
                });
        verifyNoInteractions(sessions);
    }

    @Test
    void changeMyPassword_rejectsWeakNewPassword() {
        when(encoder.matches("old-pass", "$hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changeMyPassword(auth, "old-pass", "short"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
        verifyNoInteractions(sessions);
    }

    @Test
    void changeMyPassword_updatesHash_killsOtherSessions_audits() {
        when(encoder.matches("old-pass", "$hash")).thenReturn(true);
        when(encoder.encode("newpassword1")).thenReturn("$newhash");

        service.changeMyPassword(auth, "old-pass", "newpassword1");

        verify(jdbc).update(contains("must_change_password = false"), eq("$newhash"), eq(ADMIN_ID));
        verify(sessions).revokeAllFor("admin@hydra.tn");
        verify(audit).log("PASSWORD_CHANGED", "app_user", ADMIN_ID, null);
    }

    @Test
    void changeMyPassword_keepsTheCurrentSessionAlive() {
        when(encoder.matches("old-pass", "$hash")).thenReturn(true);
        jakarta.servlet.http.HttpSession session =
                mock(jakarta.servlet.http.HttpSession.class);
        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("session-42");
        var attrs = mock(
                org.springframework.web.context.request.ServletRequestAttributes.class);
        when(attrs.getRequest()).thenReturn(request);
        org.springframework.web.context.request.RequestContextHolder
                .setRequestAttributes(attrs);
        try {
            service.changeMyPassword(auth, "old-pass", "newpassword1");
        } finally {
            org.springframework.web.context.request.RequestContextHolder
                    .resetRequestAttributes();
        }

        verify(sessions).revokeAllExcept("admin@hydra.tn", "session-42");
        verify(sessions, never()).revokeAllFor(anyString());
    }

    // ── create user ─────────────────────────────────────────────────────

    @Test
    void createUser_rejectsDuplicateEmail() {
        when(users.findByEmail("dup@hydra.tn"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), "dup@hydra.tn", false, true)));

        assertThatThrownBy(() -> service.createUser(
                auth, "Dup", "dup@hydra.tn", "password1", List.of("QA"), null))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EMAIL_EXISTS"));
    }

    @Test
    void createUser_requiresAtLeastOneRole() {
        assertThatThrownBy(() -> service.createUser(
                auth, "Nobody", "n@hydra.tn", "password1", List.of(), null))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NO_ROLE"));
    }

    @Test
    void createUser_rejectsUnknownRole() {
        assertThatThrownBy(() -> service.createUser(
                auth, "Ghost", "g@hydra.tn", "password1", List.of("GHOST"), null))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNKNOWN_ROLE"));
    }

    @Test
    void createUser_refusesToxicPairWithoutOverrideReason() {
        UUID qaId = UUID.randomUUID();
        UUID chefId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("QA"), eq(ORG))).thenReturn(List.of(qaId));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("CHEF_PROJET"), eq(ORG))).thenReturn(List.of(chefId));
        when(toxicPairs.conflictingPairs(any())).thenReturn(List.of(
                new ToxicPairService.RolePairConflict("CHEF_PROJET", "QA",
                        "The executor cannot verify his own work.")));

        assertThatThrownBy(() -> service.createUser(auth, "Split", "s@hydra.tn",
                "password1", List.of("QA", "CHEF_PROJET"), null))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TOXIC_PAIR");
                    assertThat(e.getMessage()).isEqualTo("The executor cannot verify his own work.");
                });
        verify(jdbc, never()).queryForObject(anyString(), eq(UUID.class),
                any(), any(), any(), any());
    }

    @Test
    void createUser_happyPath_assignsRole_andAudits() {
        UUID newId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.findByEmail("new@hydra.tn")).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("QA"), eq(ORG))).thenReturn(List.of(roleId));
        when(jdbc.queryForObject(anyString(), eq(UUID.class),
                any(), any(), any(), any())).thenReturn(newId);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(roleId)))
                .thenReturn(List.of("Qualité"));
        when(encoder.encode("password1")).thenReturn("$newhash");

        UserService.UserResponse r = service.createUser(
                auth, "New User", "New@Hydra.Tn", "password1", List.of("QA"), null);

        assertThat(r.id()).isEqualTo(newId);
        assertThat(r.email()).isEqualTo("New@Hydra.Tn");
        assertThat(r.isOwner()).isFalse();
        assertThat(r.isActive()).isTrue();
        assertThat(r.roleCodes()).containsExactly("QA");
        assertThat(r.roleLabels()).containsExactly("Qualité");
        verify(sessions, never()).revokeAllFor(anyString());
        verify(audit).log(eq("USER_CREATED"), eq("app_user"), eq(newId), any());
    }

    // ── deactivate / reactivate ─────────────────────────────────────────

    @Test
    void deactivate_refusesOnYourself() {
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));

        assertThatThrownBy(() -> service.deactivateUser(auth, ADMIN_ID))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SELF_ACTION"));
        verifyNoInteractions(sessions);
    }

    @Test
    void deactivate_refusesOnTheOwner() {
        AppUser owner = user(UUID.randomUUID(), "owner@hydra.tn", true, true);
        when(users.findById(owner.getId())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.deactivateUser(auth, owner.getId()))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OWNER_PROTECTED"));
        verifyNoInteractions(sessions);
    }

    @Test
    void deactivate_killsSessions_andAudits() {
        UUID targetId = UUID.randomUUID();
        AppUser target = user(targetId, "member@hydra.tn", false, true);
        when(users.findById(targetId)).thenReturn(Optional.of(target));

        service.deactivateUser(auth, targetId);

        verify(jdbc).update(contains("is_active = false"), eq(targetId));
        verify(sessions).revokeAllFor("member@hydra.tn");
        verify(audit).log(eq("USER_DEACTIVATED"), eq("app_user"), eq(targetId), any());
    }

    @Test
    void deactivate_rejectsAlreadyInactiveUser() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, false)));

        assertThatThrownBy(() -> service.deactivateUser(auth, targetId))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ALREADY_INACTIVE"));
    }

    @Test
    void reactivate_wakesTheAccount_andAudits() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, false)));

        service.reactivateUser(auth, targetId);

        verify(jdbc).update(contains("is_active = true"), eq(targetId));
        verify(audit).log(eq("USER_REACTIVATED"), eq("app_user"), eq(targetId), any());
    }

    // ── admin reset password ────────────────────────────────────────────

    @Test
    void resetPassword_rejectsWeakPassword() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));

        assertThatThrownBy(() -> service.resetPassword(auth, targetId, "short"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
    }

    @Test
    void resetPassword_forcesChange_onNextLogin_andKillsSessions() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(encoder.encode("newpassword1")).thenReturn("$newhash");

        service.resetPassword(auth, targetId, "newpassword1");

        verify(jdbc).update(contains("must_change_password = true"), eq("$newhash"), eq(targetId));
        verify(sessions).revokeAllFor("member@hydra.tn");
        verify(audit).log(eq("PASSWORD_RESET"), eq("app_user"), eq(targetId), any());
    }

    // ── list users ──────────────────────────────────────────────────────

    @Test
    void listUsers_groupsAllRolesUnderEachUser() {
        UUID userId = UUID.randomUUID();
        var row1 = new java.util.HashMap<String, Object>();
        row1.put("id", userId);
        row1.put("email", "a@hydra.tn");
        row1.put("full_name", "A");
        row1.put("is_owner", false);
        row1.put("is_active", true);
        row1.put("code", "QA");
        row1.put("label", "Qualité");
        var row2 = new java.util.HashMap<String, Object>(row1);
        row2.put("code", "FINANCE");
        row2.put("label", "Finance");
        when(jdbc.queryForList(anyString(), eq(ORG))).thenReturn(List.of(row1, row2));

        List<UserService.UserResponse> list = service.listUsers(auth);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).email()).isEqualTo("a@hydra.tn");
        assertThat(list.get(0).roleCodes()).containsExactly("QA", "FINANCE");
        assertThat(list.get(0).roleLabels()).containsExactly("Qualité", "Finance");
    }

    // ── update user roles ──────────────────────────────────────────────

    @Test
    void updateRoles_refusesToxicPair_withoutOverrideReason() {
        UUID targetId = UUID.randomUUID();
        UUID financeId = UUID.randomUUID();
        UUID achatId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("FINANCE"), eq(ORG)))
                .thenReturn(List.of(financeId));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("ACHAT"), eq(ORG)))
                .thenReturn(List.of(achatId));
        when(toxicPairs.conflictingPairs(any())).thenReturn(List.of(
                new ToxicPairService.RolePairConflict(
                        "FINANCE", "ACHAT", "The person who buys cannot be the person who pays.")));

        assertThatThrownBy(() -> service.updateUserRoles(auth, targetId,
                List.of("FINANCE", "ACHAT"), null))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TOXIC_PAIR");
                    assertThat(e.getMessage())
                            .isEqualTo("The person who buys cannot be the person who pays.");
                });
        verify(jdbc, never()).update(contains("delete from user_role"), any(Object.class));
    }

    @Test
    void updateRoles_replacesAllRows_revokesSessions_andAudits() {
        UUID targetId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("QA"), eq(ORG)))
                .thenReturn(List.of(roleId));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(roleId)))
                .thenReturn(List.of("Qualité"));
        when(toxicPairs.conflictingPairs(any())).thenReturn(List.of());

        service.updateUserRoles(auth, targetId, List.of("QA"), null);

        verify(jdbc).update(contains("delete from user_role"), eq(targetId));
        verify(jdbc).update(contains("insert into user_role"), eq(targetId), eq(roleId), eq(ADMIN_ID));
        verify(sessions).revokeAllFor("member@hydra.tn");
        verify(audit).log(eq("USER_ROLES_UPDATED"), eq("app_user"), eq(targetId), any());
    }

    @Test
    void updateRoles_withOverrideReason_recordsToxicPairOverride() {
        UUID targetId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("FINANCE"), eq(ORG)))
                .thenReturn(List.of(roleId));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(roleId)))
                .thenReturn(List.of("Finance"));
        when(toxicPairs.conflictingPairs(any())).thenReturn(List.of(
                new ToxicPairService.RolePairConflict(
                        "FINANCE", "ACHAT", "The person who buys cannot be the person who pays.")));

        service.updateUserRoles(auth, targetId, List.of("FINANCE"), "Contrôle interne exceptionnel");

        verify(jdbc).update(contains("delete from toxic_pair_override"), eq(targetId));
        verify(jdbc).update(contains("insert into toxic_pair_override"),
                eq(ORG), eq(targetId), eq("FINANCE"), eq("ACHAT"),
                eq("Contrôle interne exceptionnel"), eq(ADMIN_ID));
        verify(audit).log(eq("TOXIC_PAIR_OVERRIDDEN"), eq("app_user"), eq(targetId), any());
        verify(audit).log(eq("USER_ROLES_UPDATED"), eq("app_user"), eq(targetId), any());
        verify(sessions).revokeAllFor("member@hydra.tn");
    }

    @Test
    void updateRoles_rejectsUnknownRoleCode() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("GHOST"), eq(ORG)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.updateUserRoles(auth, targetId, List.of("GHOST"), null))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNKNOWN_ROLE"));
    }

    // ── update user ────────────────────────────────────────────────────

    @Test
    void updateUser_renames_andAudits() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));

        service.updateUser(auth, targetId, "Nouveau Nom", null);

        verify(jdbc).update(contains("full_name = ?"), eq("Nouveau Nom"), eq(targetId));
        verify(jdbc, never()).update(contains("password_hash"), any(), any());
        verifyNoInteractions(sessions);
        verify(audit).log(eq("USER_UPDATED"), eq("app_user"), eq(targetId), any());
    }

    @Test
    void updateUser_withPassword_forcesChange_andKillsSessions() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));
        when(encoder.encode("newpassword1")).thenReturn("$newhash");

        service.updateUser(auth, targetId, "Nouveau Nom", "newpassword1");

        verify(jdbc).update(contains("full_name = ?"), eq("Nouveau Nom"), eq(targetId));
        verify(jdbc).update(contains("must_change_password = true"), eq("$newhash"), eq(targetId));
        verify(sessions).revokeAllFor("member@hydra.tn");
        verify(audit).log(eq("USER_UPDATED"), eq("app_user"), eq(targetId), any());
    }

    @Test
    void updateUser_rejectsWeakPassword() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));

        assertThatThrownBy(() -> service.updateUser(auth, targetId, "Nouveau Nom", "court"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
        verify(jdbc, never()).update(contains("full_name = ?"), any(), any());
    }

    @Test
    void updateUser_rejectsBlankName() {
        UUID targetId = UUID.randomUUID();
        when(users.findById(targetId))
                .thenReturn(Optional.of(user(targetId, "member@hydra.tn", false, true)));

        assertThatThrownBy(() -> service.updateUser(auth, targetId, "  ", null))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REQUIRED_FIELDS"));
    }

    // ── user options picker ─────────────────────────────────────────────

    @Test
    void userOptions_returnsActiveUsersOfOwnOrgOnly() {
        var row = new HashMap<String, Object>();
        row.put("id", UUID.randomUUID());
        row.put("full_name", "Ali Ben");
        row.put("email", "ali@hydra.tn");
        when(jdbc.queryForList(anyString(), eq(ORG))).thenReturn(List.of(row));

        List<UserService.UserOption> options = service.userOptions(auth);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).fullName()).isEqualTo("Ali Ben");
        verify(jdbc).queryForList(contains("is_active = true"), eq(ORG));
    }
}

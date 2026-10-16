package tn.hydra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.security.FolderAccessService.FolderAction;
import tn.hydra.security.FolderAccessService.GrantRequest;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderAccessServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID FOLDER = UUID.randomUUID();
    private static final UUID ACCESS_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private EventLogService audit;
    private FolderAccessService service;
    private Authentication auth;
    private AppUser admin;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new FolderAccessService(jdbc, users, audit);
        auth = new UsernamePasswordAuthenticationToken("admin@hydra.tn", "pw");
        SecurityContextHolder.clearContext();

        admin = new AppUser();
        admin.setId(UUID.randomUUID());
        admin.setOrgId(ORG);
        admin.setEmail("admin@hydra.tn");
        admin.setIsOwner(true);
        when(users.findByEmail("admin@hydra.tn")).thenReturn(Optional.of(admin));
    }

    private Map<String, Object> folderRow(String code) {
        var row = new HashMap<String, Object>();
        row.put("id", FOLDER);
        row.put("project_id", PROJECT);
        row.put("code", code);
        return row;
    }

    private void stubFolder(String code) {
        when(jdbc.queryForList(contains("from doc_folder"), eq(FOLDER)))
                .thenReturn(List.of(folderRow(code)));
    }

    @SuppressWarnings("unchecked")
    private void stubAclQuery(boolean read, boolean upload, boolean download) {
        when(jdbc.query(contains("bool_or"), any(ResultSetExtractor.class),
                eq(FOLDER), any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    var ex = (ResultSetExtractor<Boolean>) inv.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getBoolean(1)).thenReturn(read);
                    when(rs.getBoolean(2)).thenReturn(upload);
                    when(rs.getBoolean(3)).thenReturn(download);
                    return ex.extractData(rs);
                });
    }

    @Test
    void canAccessFolder_ownerBypass_alwaysTrue() {
        stubFolder("06_FIN");
        when(jdbc.query(contains("is_owner"), any(ResultSetExtractor.class), any()))
                .thenReturn(true);

        assertThat(service.canAccessFolder(admin.getId(), FOLDER, FolderAction.READ)).isTrue();
        verify(jdbc, never()).query(contains("bool_or"), any(ResultSetExtractor.class), any(), any(), any());
    }

    @Test
    void canAccessFolder_roleGrant_readAllowed_uploadDenied() {
        stubFolder("06_FIN");
        stubAclQuery(true, false, true);
        UUID stranger = UUID.randomUUID();

        assertThat(service.canAccessFolder(stranger, FOLDER, FolderAction.READ)).isTrue();
        assertThat(service.canAccessFolder(stranger, FOLDER, FolderAction.DOWNLOAD)).isTrue();
        assertThat(service.canAccessFolder(stranger, FOLDER, FolderAction.UPLOAD)).isFalse();
        assertThat(service.canAccessFolder(stranger, FOLDER, FolderAction.DELETE)).isFalse();
    }

    // ── assertAccess ─────────────────────────────────────────────────────
    @Test
    void assertAccess_readDenied_throwsFolderNotFound_hidesExistence() {
        stubFolder("06_FIN");
        when(jdbc.query(contains("bool_or"), any(ResultSetExtractor.class),
                eq(FOLDER), any(UUID.class), any(UUID.class))).thenReturn(false);

        assertThatThrownBy(() -> service.assertAccess(UUID.randomUUID(), FOLDER, FolderAction.READ))
                .isInstanceOf(tn.hydra.exception.ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "FOLDER_NOT_FOUND");
    }

    @Test
    void assertAccess_uploadDenied_throwsAccessDenied() {
        stubFolder("06_FIN");
        when(jdbc.query(contains("bool_or"), any(ResultSetExtractor.class),
                eq(FOLDER), any(UUID.class), any(UUID.class))).thenReturn(false);

        assertThatThrownBy(() -> service.assertAccess(UUID.randomUUID(), FOLDER, FolderAction.UPLOAD))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void readableFolderIds_owner_returnsNullNoFilter() {
        assertThat(service.readableFolderIds(admin, PROJECT)).isNull();
    }

    // ── seedDefaults ─────────────────────────────────────────────────────
    @Test
    void seedDefaults_insertsMatrixRows_forFinanceFolder() {
        var folder = new HashMap<String, Object>();
        folder.put("id", FOLDER);
        folder.put("code", "06_FIN");
        when(jdbc.queryForList(contains("not exists"), eq(PROJECT))).thenReturn(List.of(folder));
        when(jdbc.update(contains("insert into folder_access"),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        int inserted = service.seedDefaults(PROJECT);

        // 06_FIN has exactly 3 matrix rows: OWNER, FINANCE, DIRECTION
        assertThat(inserted).isEqualTo(3);
        verify(audit).log(eq("FOLDER_ACCESS_SEEDED"), eq("project"), eq(PROJECT), any());
    }

    @Test
    void seedDefaults_legacyCode07INT_matchesHseMatrix() {
        var folder = new HashMap<String, Object>();
        folder.put("id", FOLDER);
        folder.put("code", "07_INT");
        when(jdbc.queryForList(contains("not exists"), eq(PROJECT))).thenReturn(List.of(folder));
        when(jdbc.update(contains("insert into folder_access"),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        // 07 prefix: OWNER, HSE, CHEF_PROJET, CONDUCTEUR = 4 rows
        assertThat(service.seedDefaults(PROJECT)).isEqualTo(4);
    }

    // ── grant ────────────────────────────────────────────────────────────
    @Test
    void grant_roleOnFinanceFolder_forSousTraitant_rejected() {
        stubFolder("06_FIN");

        assertThatThrownBy(() -> service.grant(auth, PROJECT, FOLDER,
                new GrantRequest(null, "SOUS_TRAITANT", true, false, true, false, false)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "FOLDER_ACCESS_FORBIDDEN");
        verify(jdbc, never()).update(contains("insert into folder_access"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void grant_bothRoleAndUser_rejected() {
        stubFolder("02_TECH");

        assertThatThrownBy(() -> service.grant(auth, PROJECT, FOLDER,
                new GrantRequest(UUID.randomUUID(), "QA", true, false, true, false, false)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TARGET");
    }

    @Test
    void grant_neitherRoleNorUser_rejected() {
        stubFolder("02_TECH");

        assertThatThrownBy(() -> service.grant(auth, PROJECT, FOLDER,
                new GrantRequest(null, null, true, false, true, false, false)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TARGET");
    }

    // ── revoke ───────────────────────────────────────────────────────────
    @Test
    void revoke_missingGrant_throwsNotFound() {
        stubFolder("02_TECH");
        when(jdbc.update(contains("delete from folder_access"), eq(ACCESS_ID), eq(FOLDER))).thenReturn(0);

        assertThatThrownBy(() -> service.revoke(auth, PROJECT, FOLDER, ACCESS_ID))
                .isInstanceOf(tn.hydra.exception.ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "FOLDER_ACCESS_NOT_FOUND");
    }

    @Test
    void revoke_existingGrant_deletesAndAudits() {
        stubFolder("02_TECH");
        when(jdbc.update(contains("delete from folder_access"), eq(ACCESS_ID), eq(FOLDER))).thenReturn(1);

        service.revoke(auth, PROJECT, FOLDER, ACCESS_ID);

        verify(audit).log(eq("FOLDER_ACCESS_REVOKED"), eq("doc_folder"), eq(FOLDER), contains("accessId"));
    }

    // ── matrix sanity ────────────────────────────────────────────────────
    @Test
    void matrix_neverGrantsSousTraitantOrConducteur_onFinanceFolder() {
        List<String> financeRoles = FolderAccessService.MATRIX.stream()
                .filter(m -> m.prefix().equals("06"))
                .map(FolderAccessService.MatrixRow::roleCode)
                .toList();
        assertThat(financeRoles).containsExactlyInAnyOrder("OWNER", "FINANCE", "DIRECTION");
    }
}


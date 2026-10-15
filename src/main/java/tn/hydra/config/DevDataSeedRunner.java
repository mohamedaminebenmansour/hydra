package tn.hydra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.security.FolderAccessService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dev-only seed data for folder ACL (V15) verification.
 *
 * <p>Creates, in the default organization ("Company"):
 * <ul>
 *   <li>10 test users (password {@code Test1234!}, BCrypt-hashed) covering every system role.</li>
 *   <li>3 sample projects with members holding different project roles.</li>
 *   <li>Per-project folder clones + default {@code folder_access} grants
 *       (via {@link FolderAccessService#seedDefaults(UUID)}).</li>
 * </ul>
 *
 * <p>Active only when {@code spring.profiles.active=dev} AND
 * {@code app.dev.seed.enabled=true}. Never used in production.
 */
@Component
@Profile("dev")
@Order(2)
public class DevDataSeedRunner implements CommandLineRunner {

    private static final String DEV_PASSWORD = "Test1234!";
    private static final String ORG_NAME = "Company";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final EventLogService audit;
    private final FolderAccessService folderAccess;

    @Value("${app.dev.seed.enabled:false}")
    private boolean devSeedEnabled;

    public DevDataSeedRunner(JdbcTemplate jdbc, PasswordEncoder encoder,
                             EventLogService audit, FolderAccessService folderAccess) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.folderAccess = folderAccess;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!devSeedEnabled) {
            return;
        }
        UUID orgId = ensureOrganization();
        UUID seededBy = ensureOwner(orgId);

        // ── 10 test users ──────────────────────────────────────────────
        List<UserSpec> users = List.of(
                new UserSpec("owner@hydra.tn", "Propriétaire", "OWNER", true),
                new UserSpec("chef@hydra.tn", "Chef de Projet", "CHEF_PROJET", false),
                new UserSpec("conducteur@hydra.tn", "Conducteur", "CONDUCTEUR", false),
                new UserSpec("finance@hydra.tn", "Responsable Finance", "FINANCE", false),
                new UserSpec("achat@hydra.tn", "Acheteur", "ACHAT", false),
                new UserSpec("qa@hydra.tn", "Responsable QA", "QA", false),
                new UserSpec("ingenieur@hydra.tn", "Ingénieur", "INGENIEUR", false),
                new UserSpec("hse@hydra.tn", "HSE", "HSE", false),
                new UserSpec("sousTraitant@hydra.tn", "Sous-traitant", "SOUS_TRAITANT", false),
                new UserSpec("chefChantier@hydra.tn", "Chef de Chantier", "CHEF_CHANTIER", false)
        );

        Map<String, UUID> userIds = new LinkedHashMap<>();
        for (UserSpec spec : users) {
            UUID id = insertUser(orgId, spec);
            userIds.put(spec.email(), id);
            assignRole(id, spec.roleCode(), seededBy);
            audit.logAs(orgId, id, "USER_CREATED", "app_user", id,
                    "{\"email\":\"" + spec.email() + "\"}");
        }

        // ── 3 sample projects + members + default folder ACL ───────────
        List<ProjectSpec> projects = List.of(
                new ProjectSpec("PROJ-001", "Tour de Contrôle Aéroportuaire", "ACTIF",
                        List.of(
                                m("owner@hydra.tn", "ADMIN"),
                                m("chef@hydra.tn", "MANAGER"),
                                m("conducteur@hydra.tn", "ENGINEER"),
                                m("finance@hydra.tn", "VIEWER"),
                                m("sousTraitant@hydra.tn", "VIEWER")
                        )),
                new ProjectSpec("PROJ-002", "Pont Haubané Oued Zarga", "ACTIF",
                        List.of(
                                m("chef@hydra.tn", "ADMIN"),
                                m("ingenieur@hydra.tn", "MANAGER"),
                                m("achat@hydra.tn", "ENGINEER"),
                                m("qa@hydra.tn", "VIEWER"),
                                m("hse@hydra.tn", "ENGINEER")
                        )),
                new ProjectSpec("PROJ-003", "Station d'Épuration Nord", "CLOTURE",
                        List.of(
                                m("chefChantier@hydra.tn", "ADMIN"),
                                m("conducteur@hydra.tn", "ENGINEER"),
                                m("sousTraitant@hydra.tn", "VIEWER")
                        ))
        );

        for (ProjectSpec ps : projects) {
            UUID projectId = jdbc.queryForObject("""
                    insert into project (org_id, code, name, status, created_by)
                    values (?, ?, ?, ?, ?)
                    returning id""",
                    UUID.class, orgId, ps.code(), ps.name(), ps.status(), seededBy);

            cloneFolders(projectId);
            folderAccess.seedDefaults(projectId); // idempotent, REQUIRES_NEW

            for (ProjectSpec.Member mb : ps.members()) {
                jdbc.update("""
                        insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                        values (?, ?, ?, true, ?)
                        on conflict (project_id, user_id) do nothing""",
                        projectId, userIds.get(mb.email()), mb.role(), seededBy);
            }

            audit.logAs(orgId, seededBy, "PROJECT_CREATED", "project", projectId,
                    "{\"code\":\"" + ps.code() + "\",\"name\":\"" + ps.name() + "\"}");
        }
    }

    /*──────────────────── helpers ────────────────────*/
    private UUID ensureOrganization() {
        UUID id = jdbc.queryForObject(
                "select id from organization where name = ?", UUID.class, ORG_NAME);
        if (id != null) {
            return id;
        }
        return jdbc.queryForObject(
                "insert into organization (name) values (?) returning id",
                UUID.class, ORG_NAME);
    }

    private UUID ensureOwner(UUID orgId) {
        UUID id = jdbc.queryForObject(
                "select id from app_user where email = 'owner@hydra.tn'", UUID.class);
        if (id != null) {
            return id;
        }
        return jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, 'Propriétaire', true, true) returning id""",
                UUID.class, orgId, "owner@hydra.tn", encoder.encode(DEV_PASSWORD));
    }

    private UUID insertUser(UUID orgId, UserSpec spec) {
        UUID id = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name, is_owner, is_active)
                select ?, ?, ?, ?, ?, true
                where not exists (select 1 from app_user where email = ?)
                returning id""",
                UUID.class, orgId, spec.email(), encoder.encode(DEV_PASSWORD),
                spec.fullName(), spec.isOwner(), spec.email());
        if (id == null) {
            id = jdbc.queryForObject("select id from app_user where email = ?", UUID.class, spec.email());
        }
        return id;
    }

    private void assignRole(UUID userId, String roleCode, UUID assignedBy) {
        UUID roleId = jdbc.queryForObject(
                "select id from role where org_id is null and code = ?", UUID.class, roleCode);
        if (roleId == null) {
            throw new IllegalStateException("System role not found: " + roleCode);
        }
        jdbc.update("""
                insert into user_role (user_id, role_id, assigned_by)
                values (?, ?, ?)
                on conflict (user_id, role_id) do nothing""",
                userId, roleId, assignedBy);
    }

    private void cloneFolders(UUID projectId) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from doc_folder where project_id = ?", Integer.class, projectId);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                insert into doc_folder (project_id, parent_id, code, name, sort_order, created_at)
                select ?, null, code, name, sort_order, now()
                from doc_folder_template
                where org_id is null
                order by sort_order, code""", projectId);
    }

    private static ProjectSpec.Member m(String email, String role) {
        return new ProjectSpec.Member(email, role);
    }

    record UserSpec(String email, String fullName, String roleCode, boolean isOwner) {
    }

    record ProjectSpec(String code, String name, String status, List<Member> members) {
        record Member(String email, String role) {
        }
    }
}
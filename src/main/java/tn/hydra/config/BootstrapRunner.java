package tn.hydra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tn.hydra.audit.EventLogService;

import java.util.UUID;

@Component
public class BootstrapRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final EventLogService audit;

    @Value("${app.owner.email}")    private String ownerEmail;
    @Value("${app.owner.password}") private String ownerPassword;

    public BootstrapRunner(JdbcTemplate jdbc, PasswordEncoder encoder, EventLogService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        if (count != null && count > 0) return;

        UUID orgId = jdbc.queryForObject(
                "insert into organization (name) values ('Company') returning id", UUID.class);
        UUID roleId = jdbc.queryForObject(
                "select id from role where code = 'OWNER'", UUID.class);
        UUID userId = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, 'Gérant', true, true) returning id
                """, UUID.class, orgId, ownerEmail, encoder.encode(ownerPassword));
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                userId, roleId, userId);

        audit.logAs(orgId, userId, "ORG_BOOTSTRAPPED", "organization", orgId, null);
        audit.logAs(orgId, userId, "USER_CREATED", "app_user", userId,
                "{\"email\":\"" + ownerEmail + "\"}");
    }
}
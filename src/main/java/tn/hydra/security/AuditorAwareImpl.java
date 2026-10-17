package tn.hydra.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the current user id for @CreatedBy / @LastModifiedBy.
 *
 * CRITICAL: must NOT use a JPA repository here.
 * Hibernate calls getCurrentAuditor() inside the pre-flush phase (to populate
 * @CreatedBy on new entities). If getCurrentAuditor() itself issues a JPA query,
 * Hibernate detects pending entities in the session and tries to auto-flush
 * again, which calls getCurrentAuditor() again → infinite recursion → StackOverflowError.
 *
 * Using JdbcTemplate bypasses the Hibernate session and the auto-flush mechanism
 * completely, breaking the loop.
 *
 * Returns empty() outside a request (scheduled jobs, startup) so auditing skips
 * the created_by column gracefully.
 */
@Component("auditorAwareImpl")
public class AuditorAwareImpl implements AuditorAware<UUID> {

    private final JdbcTemplate jdbc;

    public AuditorAwareImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String email = auth.getName();
        if (email == null || email.isBlank()) return Optional.empty();

        // Direct JDBC — does NOT touch the Hibernate session, no auto-flush triggered.
        UUID id = jdbc.query(
                "select id from app_user where email = ? limit 1",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                email);
        return Optional.ofNullable(id);
    }
}
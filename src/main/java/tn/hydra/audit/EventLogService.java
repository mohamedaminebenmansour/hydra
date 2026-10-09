package tn.hydra.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class EventLogService {

    private final JdbcTemplate jdbc;

    public EventLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String action, String entityType, UUID entityId, String detailsJson) {
        UUID userId = resolveUserId();
        UUID orgId = null;
        if (userId != null) {
            List<UUID> ids = jdbc.queryForList(
                "select org_id from app_user where id = ?", UUID.class, userId);
            if (!ids.isEmpty()) orgId = ids.get(0);
        }
        insert(orgId, userId, action, entityType, entityId, detailsJson);
    }

    public void logAs(UUID orgId, UUID userId, String action,
                      String entityType, UUID entityId, String detailsJson) {
        insert(orgId, userId, action, entityType, entityId, detailsJson);
    }

    private void insert(UUID orgId, UUID userId, String action,
                        String entityType, UUID entityId, String detailsJson) {
        jdbc.update("""
            insert into event_log (org_id, user_id, action, entity_type, entity_id, details)
            values (?, ?, ?, ?, ?, ?::jsonb)
            """, orgId, userId, action, entityType, entityId, detailsJson);
    }

    private UUID resolveUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return null;
        List<UUID> ids = jdbc.queryForList(
            "select id from app_user where email = ?", UUID.class, a.getName());
        return ids.isEmpty() ? null : ids.get(0);
    }
}
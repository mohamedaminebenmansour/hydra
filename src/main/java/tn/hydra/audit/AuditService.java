package tn.hydra.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public AuditService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record EventResponse(Long id, java.time.Instant createdAt, UUID userId,
                                String userEmail, String userFullName, String action,
                                String entityType, UUID entityId, String details) {}

    public record UserSummaryResponse(java.time.Instant lastLoginAt, java.time.Instant lastActionAt) {}

    // ══════════════════ 1. ACTIVITY FEED ══════════════════

    public List<EventResponse> events(Authentication auth,
                                      UUID userId, LocalDate from, LocalDate to,
                                      String action, Integer page, Integer size) {
        AppUser caller = users.findByEmail(auth.getName()).orElseThrow();

        int pageSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        int pageNumber = page == null || page < 0 ? 0 : page;

        StringBuilder sql = new StringBuilder("""
                select e.id, e.created_at, e.user_id, u.email, u.full_name,
                       e.action, e.entity_type, e.entity_id, e.details
                from event_log e
                left join app_user u on u.id = e.user_id
                where e.org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(caller.getOrgId());

        if (userId != null) {
            sql.append(" and e.user_id = ?");
            args.add(userId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" and e.action = ?");
            args.add(action.trim());
        }
        if (from != null) {
            sql.append(" and e.created_at >= ?");
            args.add(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        if (to != null) {
            // half-open interval: the whole "to" day is included
            sql.append(" and e.created_at < ?");
            args.add(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        sql.append(" order by e.created_at desc, e.id desc limit ? offset ?");
        args.add(pageSize);
        args.add(pageNumber * pageSize);

        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(AuditService::toEvent)
                .toList();
    }

    // ══════════════════ 2. PER-USER SUMMARY ══════════════════

    public UserSummaryResponse userSummary(Authentication auth, UUID id) {
        AppUser caller = users.findByEmail(auth.getName()).orElseThrow();
        List<UUID> orgs = jdbc.queryForList(
                "select org_id from app_user where id = ?", UUID.class, id);
        if (orgs.isEmpty() || !caller.getOrgId().equals(orgs.get(0)))
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");

        List<Map<String, Object>> row = jdbc.queryForList("""
                select max(created_at) filter (where action = 'LOGIN_SUCCESS') as last_login,
                       max(created_at) as last_action
                from event_log
                where org_id = ? and user_id = ?
                """, caller.getOrgId(), id);
        Object lastLogin = row.isEmpty() ? null : row.get(0).get("last_login");
        Object lastAction = row.isEmpty() ? null : row.get(0).get("last_action");
        return new UserSummaryResponse(toInstant(lastLogin), toInstant(lastAction));
    }

    // ══════════════════ HELPERS ══════════════════

    private static EventResponse toEvent(Map<String, Object> row) {
        return new EventResponse(
                ((Number) row.get("id")).longValue(),
                toInstant(row.get("created_at")),
                (UUID) row.get("user_id"),
                (String) row.get("email"),
                (String) row.get("full_name"),
                (String) row.get("action"),
                (String) row.get("entity_type"),
                (UUID) row.get("entity_id"),
                row.get("details") == null ? null : row.get("details").toString());
    }

    private static java.time.Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return java.time.Instant.parse(value.toString());
    }
}

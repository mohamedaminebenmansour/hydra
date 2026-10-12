package tn.hydra.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> permissionsOf(UUID userId) {
        return jdbc.queryForList("""
                select distinct p.id
                from user_role ur
                join role_permission rp on rp.role_id = ur.role_id
                join permission p on p.id = rp.permission_id
                where ur.user_id = ?
                """, String.class, userId)
                .stream()
                .map(p -> "PERM_" + p)
                .collect(Collectors.toSet());
    }

    /** Every permission in the catalogue, as authorities. Used for is_owner accounts. */
    public Set<String> allPermissions() {
        return jdbc.queryForList("select id from permission", String.class)
                .stream()
                .map(p -> "PERM_" + p)
                .collect(Collectors.toSet());
    }
}
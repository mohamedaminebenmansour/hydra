package tn.hydra.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionRevoker {

    private final JdbcTemplate jdbc;

    public SessionRevoker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Kills every active session of this person. They are logged out instantly, everywhere. */
    public void revokeAllFor(String email) {
        jdbc.update("delete from spring_session where principal_name = ?", email);
    }

    /** Like {@link #revokeAllFor} but keeps one session alive (the caller's own). */
    public void revokeAllExcept(String email, String keepSessionId) {
        jdbc.update("delete from spring_session where principal_name = ? and session_id <> ?",
                email, keepSessionId);
    }
}

package tn.hydra.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SessionRevokerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionRevoker revoker = new SessionRevoker(jdbc);

    @Test
    void revokeAllForKillsEverySessionOfThatPerson() {
        revoker.revokeAllFor("qa@hydra.tn");

        verify(jdbc).update("delete from spring_session where principal_name = ?", "qa@hydra.tn");
    }

    @Test
    void revokeAllExceptKeepsTheCallersOwnSession() {
        revoker.revokeAllExcept("qa@hydra.tn", "current-session-id");

        verify(jdbc).update(contains("and session_id <> ?"),
                eq("qa@hydra.tn"), eq("current-session-id"));
    }
}

package tn.hydra.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ToxicPairService {

    private final JdbcTemplate jdbc;

    public ToxicPairService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RolePairConflict(String roleA, String roleB, String reason) {}

    /** Returns the refusal sentence if this set of role codes contains a forbidden combination. */
    public Optional<String> findConflict(Set<String> roleCodes) {
        List<RolePairConflict> conflicts = conflictingPairs(roleCodes);
        return conflicts.isEmpty() ? Optional.empty() : Optional.of(conflicts.get(0).reason());
    }

    /** Every forbidden ordered pair found in the set, so callers can record each override. */
    public List<RolePairConflict> conflictingPairs(Set<String> roleCodes) {
        List<RolePairConflict> found = new ArrayList<>();
        for (String a : roleCodes) {
            for (String b : roleCodes) {
                if (!a.equals(b)) {
                    List<String> reasons = jdbc.queryForList(
                            "select reason from incompatible_role_pair where role_a = ? and role_b = ?",
                            String.class, a, b);
                    if (!reasons.isEmpty()) found.add(new RolePairConflict(a, b, reasons.get(0)));
                }
            }
        }
        return found;
    }
}

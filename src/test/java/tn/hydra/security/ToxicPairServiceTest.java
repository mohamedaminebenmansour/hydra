package tn.hydra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToxicPairServiceTest {

    private static final String EXECUTOR_REASON = "The executor cannot verify his own work.";

    private ToxicPairService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service = new ToxicPairService(jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("CHEF_PROJET"), eq("QA")))
                .thenReturn(List.of(EXECUTOR_REASON));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("QA"), eq("CHEF_PROJET")))
                .thenReturn(List.of(EXECUTOR_REASON));
    }

    @Test
    void findConflictReturnsRefusalSentence() {
        assertThat(service.findConflict(Set.of("CHEF_PROJET", "QA")))
                .contains(EXECUTOR_REASON);
    }

    @Test
    void findConflictIsEmptyWhenRolesAreCompatible() {
        assertThat(service.findConflict(Set.of("QA", "FINANCE"))).isEmpty();
    }

    @Test
    void conflictingPairsReturnsEveryForbiddenDirection() {
        List<ToxicPairService.RolePairConflict> conflicts =
                service.conflictingPairs(Set.of("CHEF_PROJET", "QA"));

        assertThat(conflicts).hasSize(2);
        assertThat(conflicts)
                .extracting(ToxicPairService.RolePairConflict::reason)
                .containsOnly(EXECUTOR_REASON);
    }
}

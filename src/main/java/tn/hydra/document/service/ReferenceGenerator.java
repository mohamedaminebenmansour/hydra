package tn.hydra.document.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates a globally-unique internal document reference from a Postgres sequence
 * (race-free). Format: <PROJECTCODE or PRJ>-<5-digit sequence>, e.g. SONEDE-01-00007.
 */
@Service
public class ReferenceGenerator {

    private final JdbcTemplate jdbc;

    public ReferenceGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(String projectCode) {
        Long seq = jdbc.queryForObject("select nextval('doc_reference_seq')", Long.class);
        long value = (seq == null) ? 0L : seq;
        String prefix = (projectCode == null || projectCode.isBlank()) ? "PRJ" : projectCode.trim();
        return prefix + "-" + String.format("%05d", value);
    }
}
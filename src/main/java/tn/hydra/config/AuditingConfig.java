package tn.hydra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing so @CreatedDate / @LastModifiedDate / @CreatedBy
 * on tn.hydra.document.domain.Auditable are populated automatically.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAwareImpl")
public class AuditingConfig {
}
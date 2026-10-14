package tn.hydra.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the current user id for @CreatedBy. The authenticated principal carries
 * the email (username); we look up the AppUser to get its id. Returns empty outside
 * a request (e.g. scheduled jobs) so auditing simply skips the created_by column.
 */
@Component("auditorAwareImpl")
public class AuditorAwareImpl implements AuditorAware<UUID> {

    private final AppUserRepository users;

    public AuditorAwareImpl(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<UUID> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return users.findByEmail(auth.getName()).map(AppUser::getId);
    }
}
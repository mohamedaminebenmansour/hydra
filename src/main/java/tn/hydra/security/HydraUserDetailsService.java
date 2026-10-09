package tn.hydra.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.List;

@Service
public class HydraUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final PermissionService permissions;

    public HydraUserDetailsService(AppUserRepository users, PermissionService permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    @Override
    public User loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser u = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(email));
        List<SimpleGrantedAuthority> authorities = permissions.permissionsOf(u.getId())
                .stream().map(SimpleGrantedAuthority::new).toList();
        return User.withUsername(u.getEmail())
                .password(u.getPasswordHash())
                .authorities(authorities)
                .disabled(!Boolean.TRUE.equals(u.getIsActive()))
                .build();
    }
}
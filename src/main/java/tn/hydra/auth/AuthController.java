package tn.hydra.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import tn.hydra.audit.EventLogService;
import tn.hydra.security.PermissionService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final AppUserRepository users;
    private final PermissionService permissions;
    private final EventLogService audit;
    private final tn.hydra.user.UserService userService;

    public AuthController(AuthenticationManager authManager, AppUserRepository users,
                          PermissionService permissions, EventLogService audit,
                          tn.hydra.user.UserService userService) {
        this.authManager = authManager;
        this.users = users;
        this.permissions = permissions;
        this.audit = audit;
        this.userService = userService;
    }

    public record LoginRequest(String email, String password) {}
    public record MeResponse(UUID id, String email, String fullName, boolean isOwner, boolean mustChangePassword, Set<String> permissions) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.email(), body.password()));
        } catch (AuthenticationException e) {
            audit.log("LOGIN_FAILURE", null, null, "{\"attemptedEmail\":\"" + body.email() + "\"}");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "LOGIN_FAILED", "message", "Email ou mot de passe incorrect"));
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        audit.log("LOGIN_SUCCESS", "app_user", null, "{\"email\":\"" + body.email() + "\"}");
        return ResponseEntity.ok(meResponse(body.email()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(meResponse(auth.getName()));
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest body,
                                            Authentication auth) {
        userService.changeMyPassword(auth, body.oldPassword(), body.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        audit.log("LOGOUT", null, null, null);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.ok().build();
    }

    private MeResponse meResponse(String email) {
        AppUser u = users.findByEmail(email).orElseThrow();
        Set<String> perms = Boolean.TRUE.equals(u.getIsOwner())
                ? permissions.allPermissions()
                : permissions.permissionsOf(u.getId());
        return new MeResponse(u.getId(), u.getEmail(), u.getFullName(),
                Boolean.TRUE.equals(u.getIsOwner()),
                Boolean.TRUE.equals(u.getMustChangePassword()),
                perms);
    }
}
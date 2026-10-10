package tn.hydra.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public record CreateUserRequest(String fullName, String email, String password,
                                    List<String> roleCodes, String overrideReason) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
    public record ResetPasswordRequest(String newPassword) {}

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public List<UserService.UserResponse> list(Authentication auth) {
        return service.listUsers(auth);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public UserService.UserResponse create(@RequestBody CreateUserRequest body, Authentication auth) {
        return service.createUser(auth, body.fullName(), body.email(),
                body.password(), body.roleCodes(), body.overrideReason());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_USER_DEACTIVATE')")
    public ResponseEntity<?> deactivate(@PathVariable UUID id, Authentication auth) {
        service.deactivateUser(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERM_USER_DEACTIVATE')")
    public ResponseEntity<?> reactivate(@PathVariable UUID id, Authentication auth) {
        service.reactivateUser(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public ResponseEntity<?> resetPassword(@PathVariable UUID id,
                                           @RequestBody ResetPasswordRequest body,
                                           Authentication auth) {
        service.resetPassword(auth, id, body.newPassword());
        return ResponseEntity.ok().build();
    }
}

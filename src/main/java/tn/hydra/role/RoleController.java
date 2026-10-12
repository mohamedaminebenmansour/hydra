package tn.hydra.role;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    public record CreateRoleRequest(String label, List<String> permissionCodes) {}
    public record UpdateRoleRequest(String label, List<String> permissionCodes) {}

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_ROLE_MANAGE','PERM_ROLE_ASSIGN','PERM_USER_CREATE')")
    public List<RoleService.RoleResponse> list(Authentication auth) {
        return service.listRoles(auth);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public List<RoleService.PermissionResponse> permissions() {
        return service.listPermissions();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public RoleService.RoleResponse create(@RequestBody CreateRoleRequest body, Authentication auth) {
        return service.createRole(auth, body.label(), body.permissionCodes());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public RoleService.RoleResponse update(@PathVariable UUID id, @RequestBody UpdateRoleRequest body,
                                           Authentication auth) {
        return service.updateRole(auth, id, body.label(), body.permissionCodes());
    }
}

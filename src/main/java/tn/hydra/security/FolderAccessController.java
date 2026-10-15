package tn.hydra.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.security.FolderAccessService.AccessResponse;
import tn.hydra.security.FolderAccessService.GrantRequest;

import java.util.List;
import java.util.UUID;

/**
 * Folder ACL endpoints. Coarse gate = PERM_PROJECT_READ; the service enforces
 * the real rule (org owner, project ADMIN, or PERM_FOLDER_MANAGE holder).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/folders/{folderId}/access")
public class FolderAccessController {

    private final FolderAccessService service;

    public FolderAccessController(FolderAccessService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<AccessResponse> list(@PathVariable UUID projectId,
                                     @PathVariable UUID folderId,
                                     Authentication auth) {
        return service.list(auth, projectId, folderId);
    }

    /** Grant or update (upsert on folder+role / folder+user). */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public AccessResponse grant(@PathVariable UUID projectId,
                                @PathVariable UUID folderId,
                                @RequestBody GrantRequest body,
                                Authentication auth) {
        return service.grant(auth, projectId, folderId, body);
    }

    @DeleteMapping("/{accessId}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public ResponseEntity<?> revoke(@PathVariable UUID projectId,
                                    @PathVariable UUID folderId,
                                    @PathVariable UUID accessId,
                                    Authentication auth) {
        service.revoke(auth, projectId, folderId, accessId);
        return ResponseEntity.noContent().build();
    }
}

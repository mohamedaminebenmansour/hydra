package tn.hydra.project;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    public record AddMemberRequest(UUID userId, String projectRole, Boolean canUpload) {}
    public record UpdateMemberRequest(String projectRole, Boolean canUpload) {}

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<ProjectService.ProjectResponse> list(Authentication auth) {
        return service.list(auth);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_WRITE','PERM_TICKET_ASSIGN','PERM_PROJECT_READ')")
    public List<ProjectService.ProjectOption> options(Authentication auth) {
        return service.projectOptions(auth);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public ResponseEntity<ProjectService.ProjectResponse> get(@PathVariable UUID id,
                                                              Authentication auth) {
        ProjectService.ProjectResponse project = service.get(auth, id);
        return project == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(project);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public ProjectService.ProjectResponse create(@RequestBody ProjectService.CreateProjectRequest body,
                                                 Authentication auth) {
        return service.create(auth, body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public ProjectService.ProjectResponse update(@PathVariable UUID id,
                                                 @RequestBody ProjectService.UpdateProjectRequest body,
                                                 Authentication auth) {
        return service.update(auth, id, body);
    }

    // ── folders ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/folders")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<ProjectService.FolderResponse> folders(@PathVariable UUID id, Authentication auth) {
        return service.listFolders(auth, id);
    }

    @PostMapping("/{id}/ensure-folders")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public ResponseEntity<?> ensureFolders(@PathVariable UUID id, Authentication auth) {
        service.ensureFolders(auth, id);
        return ResponseEntity.ok().build();
    }

    // ── members ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<ProjectService.MemberResponse> members(@PathVariable UUID id, Authentication auth) {
        return service.listMembers(auth, id);
    }

    /** Caller's effective rights on the project (drives UI gating, e.g. upload button). */
    @GetMapping("/{id}/me")
    @PreAuthorize("hasAnyAuthority('PERM_PROJECT_READ','PERM_DOCUMENT_READ')")
    public ProjectService.MyPermissionsResponse me(@PathVariable UUID id, Authentication auth) {
        return service.myPermissions(auth, id);
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyAuthority('PERM_PROJECT_ASSIGN','PERM_PROJECT_WRITE')")
    public ProjectService.MemberResponse addMember(@PathVariable UUID id,
                                                   @RequestBody AddMemberRequest body,
                                                   Authentication auth) {
        return service.addMember(auth, id, body.userId(), body.projectRole(), body.canUpload());
    }

    @PutMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAnyAuthority('PERM_PROJECT_ASSIGN','PERM_PROJECT_WRITE')")
    public ProjectService.MemberResponse updateMember(@PathVariable UUID id,
                                                      @PathVariable UUID userId,
                                                      @RequestBody UpdateMemberRequest body,
                                                      Authentication auth) {
        return service.updateMember(auth, id, userId, body.projectRole(), body.canUpload());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAnyAuthority('PERM_PROJECT_ASSIGN','PERM_PROJECT_WRITE')")
    public ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable UUID userId,
                                          Authentication auth) {
        service.removeMember(auth, id, userId);
        return ResponseEntity.ok().build();
    }
}

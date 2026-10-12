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
        return service.create(auth, body.name(), body.code());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public ProjectService.ProjectResponse update(@PathVariable UUID id,
                                                 @RequestBody ProjectService.UpdateProjectRequest body,
                                                 Authentication auth) {
        return service.update(auth, id, body.name(), body.code(), body.status());
    }
}
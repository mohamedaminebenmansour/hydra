package tn.hydra.document.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.document.dto.CreateTagRequest;
import tn.hydra.document.dto.TagResponse;
import tn.hydra.document.service.TagService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public List<TagResponse> list(Authentication auth) {
        return tags.list(auth);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<TagResponse> create(@RequestBody CreateTagRequest body, Authentication auth) {
        return ResponseEntity.status(201).body(tags.create(auth, body.name()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        tags.delete(auth, id);
        return ResponseEntity.noContent().build();
    }
}
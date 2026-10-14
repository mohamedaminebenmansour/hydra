package tn.hydra.document.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.document.domain.Tag;
import tn.hydra.document.dto.DocumentMapper;
import tn.hydra.document.dto.TagResponse;
import tn.hydra.document.repository.TagRepository;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.List;

@Service
public class TagService {

    private final TagRepository tags;
    private final AppUserRepository users;
    private final DocumentMapper mapper;
    private final EventLogService audit;

    public TagService(TagRepository tags, AppUserRepository users,
                      DocumentMapper mapper, EventLogService audit) {
        this.tags = tags; this.users = users; this.mapper = mapper; this.audit = audit;
    }

    public List<TagResponse> list(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return tags.findByOrgIdOrderByNameAsc(me.getOrgId()).stream()
                .map(mapper::toTagResponse).toList();
    }

    @Transactional
    public TagResponse create(Authentication auth, String rawName) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) throw new BadRequestException("REQUIRED_FIELDS", "Le nom du tag est obligatoire");
        if (tags.existsByOrgIdAndNameIgnoreCase(me.getOrgId(), name))
            throw new BadRequestException("TAG_EXISTS", "Ce tag existe déjà");

        Tag tag = new Tag();
        tag.setOrgId(me.getOrgId());
        tag.setName(name);
        tags.save(tag);
        audit.log("TAG_CREATED", "tag", tag.getId(), "{\"name\":\"" + name + "\"}");
        return mapper.toTagResponse(tag);
    }

    @Transactional
    public void delete(Authentication auth, java.util.UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Tag tag = tags.findByIdAndOrgId(id, me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("TAG_NOT_FOUND", "Tag introuvable"));
        tags.delete(tag); // detaches from documents via join table cascade
        audit.log("TAG_DELETED", "tag", tag.getId(), null);
    }
}
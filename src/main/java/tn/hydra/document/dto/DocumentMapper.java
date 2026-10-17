package tn.hydra.document.dto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import tn.hydra.document.domain.Document;
import tn.hydra.document.domain.DocumentRevision;
import tn.hydra.document.domain.Tag;

import java.util.List;

/**
 * Entity <-> DTO mapping. Derived fields (project from folder, ticket id null-safe,
 * tag names, revision count, uploader/time) are wired with explicit mappings.
 *
 * <p>Read paths (search/register/get/byReference) fetch folder&#8594;project, tags and
 * revisions <b>eagerly</b> in the repository queries (SELECT DISTINCT + JOIN FETCH), so
 * mapping never touches an uninitialized lazy association even though
 * spring.jpa.open-in-view=false. The ticket id is read proxy-safely (getId() does not
 * initialize the lazy association). Write paths (create/update/linkTicket) map inside
 * an active transaction where lazy loading still works as a fallback.
 */
@Mapper(componentModel = "spring")
public interface DocumentMapper {

    @Mapping(target = "folderId", source = "folder.id")
    @Mapping(target = "projectId", source = "folder.project.id")
    @Mapping(target = "ticketId",
            expression = "java(document.getTicket() == null ? null : document.getTicket().getId())")
    @Mapping(target = "tags",
            expression = "java(document.getTags() == null ? java.util.List.of() : document.getTags().stream().map(Tag::getName).sorted().toList())")
    @Mapping(target = "revisionCount", expression = "java(document.getRevisions() == null ? 0 : document.getRevisions().size())")
    DocumentResponse toResponse(Document document);

    @Mapping(target = "uploadedBy", source = "createdBy")
    @Mapping(target = "uploadedAt", source = "createdAt")
    RevisionResponse toRevisionResponse(DocumentRevision revision);

    List<RevisionResponse> toRevisionResponses(List<DocumentRevision> revisions);

    TagResponse toTagResponse(Tag tag);
}
package tn.hydra.document.domain;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Org-scoped document tag (many-to-many with documents). */
@Entity
@Table(name = "tag")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id", callSuper = false)
public class Tag extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    private UUID orgId;
    private String name;
}
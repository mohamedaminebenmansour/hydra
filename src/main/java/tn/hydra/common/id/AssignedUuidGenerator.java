package tn.hydra.common.id;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

import java.util.EnumSet;
import java.util.UUID;

/**
 * UUID identifier generator that honors a pre-assigned identifier.
 *
 * <p>{@code @UuidGenerator} unconditionally overwrites the id with a freshly generated UUID
 * (its {@code allowAssignedIdentifiers()} returns {@code false}), which silently breaks
 * flows that pre-generate an id before {@code persist()} — e.g. Document upload, where the
 * S3 storage key embeds the pre-generated {@code docId}. This generator keeps the
 * pre-assigned value and only generates a random UUID when the id is null.</p>
 *
 * <p>Registered on entities via the {@link AssignedUuid} annotation
 * (meta-annotated with {@code @IdGeneratorType}).</p>
 */
public class AssignedUuidGenerator implements BeforeExecutionGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner,
                           Object currentValue, EventType eventType) {
        return currentValue != null ? currentValue : UUID.randomUUID();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    @Override
    public boolean allowAssignedIdentifiers() {
        return true; // let Hibernate accept the pre-assigned id on a new entity
    }
}
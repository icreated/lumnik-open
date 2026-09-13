package io.lumnik.hub.connector.persistence.converters;

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;

/**
 * The mutability plan every {@code JsonNode} column is mapped with.
 *
 * <p>Hibernate's default plan for a JSON-mapped {@code JsonNode} is
 * {@code MutableMutabilityPlan.INSTANCE}, and the basic-type dirty check treats that plan as
 * "always dirty" without comparing anything — so a transaction that merely LOADS the entity
 * flushes it back as a full-row UPDATE with its stale copy: a lost update for whatever finished
 * meanwhile (a run listed by {@code lm run list} stayed {@code Running} for ever). With this plan
 * the snapshot is the same instance: unchanged means not dirty, and a setter call — a new
 * reference — is still written. Not {@code @Immutable}, which would make the column non-updatable
 * and drop every setter silently.
 *
 * <p>What carries it to a {@code @JdbcTypeCode(SqlTypes.JSON)} column is
 * {@link JsonNodeTypeContributor}, not the {@code @Mutability} on the field — see there for why.
 * A column mapped through an {@code AttributeConverter} is the exception: that path resolves a
 * plan per attribute and does read the annotation, which is why
 * {@code ConnectorCursorEntity.cursorValue} was the only column still correct on a boot where
 * every other one was mutable.
 */
public final class ImmutableJsonNodePlan extends ImmutableMutabilityPlan<JsonNode> {
}

package io.lumnik.hub.connector.persistence.converters;

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.boot.model.TypeContributor;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.descriptor.java.spi.JsonJavaType;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * One {@code JavaType} for {@code JsonNode}, registered before any entity is bound — because
 * Hibernate keeps exactly one and lets the first attribute that asks for it decide how every
 * other one behaves.
 *
 * <p>{@code BasicValue.javaType()} returns the already-registered descriptor when there is one,
 * and only builds a {@code JsonJavaType} carrying <em>that attribute's</em>
 * {@code @Mutability} when the registry is still empty. So the first {@code JsonNode} column
 * processed sets the mutability plan for all of them, and every other {@code @Mutability} on a
 * {@code JsonNode} column is dead. The plan matters far beyond copying: Hibernate's basic-type
 * dirty check short-circuits on it —
 *
 * <pre>{@code
 * // AbstractStandardBasicType
 * return getMutabilityPlan() == MutableMutabilityPlan.INSTANCE || !isSame( old, current );
 * }</pre>
 *
 * — so under the mutable plan a JSON column is dirty on every read, values never compared, and a
 * transactional read of the run ledger writes the row back: the lost update {@code
 * TransactionalReadIsNotAWriteIT} exists for.
 *
 * <p>Measured (#529). On the tree as the ticket found it, after a clean:
 * {@code TransactionalReadIsNotAWriteIT} went red <b>once in fourteen</b> consecutive runs — the
 * ticket's "once in three full verifies", reproduced on untouched source. With this contributor
 * registering the descriptor first: the resolved plan of {@code ConnectorRun.triggeredMeta} was
 * immutable on <b>10 boots of 10</b>; and a control that registered
 * {@code MutableMutabilityPlan.INSTANCE} from this very method turned every JSON column mutable,
 * 3 of 3. The registration is what decides. The per-field {@code @Mutability} stays where it is:
 * it is the declaration of intent, and the one column mapped through an {@code AttributeConverter}
 * genuinely reads it — that path resolves a plan per attribute and was the only column still
 * correct while the others were mutable.
 *
 * <p>{@code JsonJavaType} is {@code @Incubating}: it is the very class Hibernate builds for this
 * case, so we build the same one and only choose its plan.
 *
 * <p>Keyed on {@code JsonNode} alone. A field declared as {@code ObjectNode} or {@code ArrayNode}
 * is a different registry key and would fall back to the per-attribute path — none exists today,
 * and the sweep in {@code TransactionalReadIsNotAWriteIT} matches on {@code isAssignableFrom}, so
 * the day one appears it is named rather than silently decided elsewhere.
 */
public class JsonNodeTypeContributor implements TypeContributor {

    @Override
    public void contribute(TypeContributions contributions, ServiceRegistry serviceRegistry) {
        TypeConfiguration typeConfiguration = contributions.getTypeConfiguration();
        typeConfiguration.getJavaTypeRegistry().addDescriptor(
            new JsonJavaType<>(JsonNode.class, new ImmutableJsonNodePlan(), typeConfiguration));
    }
}

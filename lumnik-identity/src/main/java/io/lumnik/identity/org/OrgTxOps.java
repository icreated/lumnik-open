package io.lumnik.identity.org;

import io.lumnik.common.eventing.DomainEventPublisher;
import io.lumnik.identity.Require;
import io.lumnik.identity.geo.Coords;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The persistence work of {@link OrgResource#create} and {@link OrgResource#update}, split out so
 * {@code @Transactional} spans only the DB round-trip — the geocoder's blocking HTTP call is
 * resolved by the resource beforehand and handed in as {@code coords}. CDI self-invocation
 * doesn't intercept {@code @Transactional}, hence a separate bean rather than a private method.
 */
@ApplicationScoped
class OrgTxOps {

    @Inject OrgRepository orgs;
    @Inject DomainEventPublisher events;

    @Transactional
    Organization create(Long tenantId, OrgCommands.Create cmd, Coords coords) {
        Organization o = new Organization(tenantId, cmd.code(), cmd.name());
        o.setDescription(cmd.description());
        o.setSummary(cmd.summary());
        if (cmd.parentOrgId() != null) {
            o.setParentOrgId(cmd.parentOrgId());
        }
        o.setAddressLine1(cmd.addressLine1());
        o.setAddressLine2(cmd.addressLine2());
        o.setPostalCode(cmd.postalCode());
        o.setCity(cmd.city());
        o.setCountry(cmd.country());
        applyCoords(o, coords);
        orgs.persist(o);
        events.publish(tenantId, "org.created", OrgDto.from(o));
        return o;
    }

    @Transactional
    Organization update(Long tenantId, Long id, OrgCommands.Update cmd, Coords coords) {
        Organization o = Require.owned(orgs, id, tenantId, "Organization");
        Long oldParentId = o.getParentOrgId();
        o.setName(cmd.name());
        o.setDescription(cmd.description());
        o.setSummary(cmd.summary());
        o.setParentOrgId(cmd.parentOrgId());
        o.setAddressLine1(cmd.addressLine1());
        o.setAddressLine2(cmd.addressLine2());
        o.setPostalCode(cmd.postalCode());
        o.setCity(cmd.city());
        o.setCountry(cmd.country());
        applyCoords(o, coords);
        events.publish(tenantId, "org.updated", OrgDto.from(o));
        if (!Objects.equals(oldParentId, o.getParentOrgId())) {
            Map<String, Object> moved = new HashMap<>();
            moved.put("id", o.getId());
            moved.put("oldParentId", oldParentId);
            moved.put("newParentId", o.getParentOrgId());
            events.publish(tenantId, "org.moved", moved);
        }
        return o;
    }

    private void applyCoords(Organization o, Coords coords) {
        if (coords == null) return;
        o.setLatitude(coords.latitude());
        o.setLongitude(coords.longitude());
        o.setGeocodedAt(Instant.now());
    }
}

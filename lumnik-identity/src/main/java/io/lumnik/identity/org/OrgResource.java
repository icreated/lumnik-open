package io.lumnik.identity.org;

import io.lumnik.common.eventing.DomainEventPublisher;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.IdentityErrors;
import io.lumnik.identity.Require;
import io.lumnik.identity.TenantInPath;
import io.lumnik.identity.geo.AddressInput;
import io.lumnik.identity.geo.Coords;
import io.lumnik.identity.geo.Geocoder;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@TenantInPath
@Path("/api/tenants/{tenantId}/orgs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Organizations")
@RequiresPermission("lm_admin")
public class OrgResource {

    @Inject OrgRepository orgs;
    @Inject TenantRepository tenants;
    @Inject Geocoder geocoder;
    @Inject DomainEventPublisher events;
    @Inject OrgTxOps orgTxOps;

    @GET
    @Operation(summary = "List active organizations for a tenant")
    @Transactional
    public List<OrgDto> listAll(@PathParam("tenantId") Long tenantId) {
        requireTenant(tenantId);
        return orgs.listAll().stream().map(OrgDto::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get organization by ID")
    @Transactional
    public OrgDto findById(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        return OrgDto.from(requireOrg(tenantId, id));
    }

    @POST
    @Operation(summary = "Create an organization")
    public Response create(@PathParam("tenantId") Long tenantId, @Valid OrgCommands.Create cmd) {
        requireTenant(tenantId);
        // The checks read identity.org, whose RLS policy is bound with SET LOCAL — transaction-scoped.
        // They run before the transaction on purpose (see below), so they open their own: read
        // outside one, every parent was a 404 and a duplicate code reached the unique constraint.
        QuarkusTransaction.requiringNew().run(() -> {
            orgs.holderOfCode(tenantId, cmd.code()).ifPresent(holder -> {
                throw IdentityErrors.conflict(
                    "org code " + holder.getCode() + " is already held by org " + holder.getId());
            });
            if (cmd.parentOrgId() != null) {
                requireOrg(tenantId, cmd.parentOrgId());
            }
        });

        // Geocoding is a blocking HTTP round-trip; resolve it before opening a transaction so a
        // slow/hung Nominatim call never holds a DB connection.
        AddressInput addr = new AddressInput(cmd.addressLine1(), cmd.postalCode(), cmd.city(), cmd.country());
        Coords coords = resolveGeocode(addr, null, null, null);

        Organization o = orgTxOps.create(tenantId, cmd, coords);

        return Response.created(UriBuilder.fromResource(OrgResource.class)
                .path("/{id}").build(tenantId, o.getId()))
            .entity(OrgDto.from(o))
            .build();
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update organization name, description, and hierarchy")
    public OrgDto update(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id,
                         @Valid OrgCommands.Update cmd) {
        // Same reason as create(): these reads need a transaction of their own to see the tenant.
        Organization o = QuarkusTransaction.requiringNew().call(() -> {
            Organization current = requireOrg(tenantId, id);
            if (cmd.parentOrgId() != null) {
                requireOrg(tenantId, cmd.parentOrgId());
                requireNoCycle(tenantId, id, cmd.parentOrgId());
            }
            return current;
        });

        // Same as create(): resolve the geocode before the transaction, using the pre-update
        // (still-current) coordinates/timestamp to decide whether a re-geocode is even needed.
        AddressInput addr = new AddressInput(cmd.addressLine1(), cmd.postalCode(), cmd.city(), cmd.country());
        Coords coords = resolveGeocode(addr, o.getGeocodedAt(), o.getLatitude(), o.getLongitude());

        return OrgDto.from(orgTxOps.update(tenantId, id, cmd, coords));
    }

    /**
     * Walks {@code proposedParentId} up its ancestor chain; rejects if {@code id} appears in it
     * (re-parenting an org under its own descendant), which would make {@link #buildNode} recurse
     * forever. Bounded by the tenant's org count (archived rows included, since the walk
     * doesn't filter them) as a defense-in-depth stop against pre-existing corrupt data
     * forming a cycle that doesn't involve {@code id} at all.
     */
    private void requireNoCycle(Long tenantId, Long id, Long proposedParentId) {
        long maxSteps = orgs.countByTenant(tenantId);
        Long current = proposedParentId;
        for (long steps = 0; current != null && steps <= maxSteps; steps++) {
            if (current.equals(id)) {
                String msg = "Organization cycle: " + id + " is an ancestor of the proposed parent";
                // A plain BadRequestException(String) only sets the exception message, not the
                // response body — build the Response explicitly, with the uniform {"error": …}
                // refusal shape, so the caller can see why. The hub's BadRequestExceptionMapper does
                // exactly this deployment-wide, but it CANNOT be relied on here: lumnik-identity does
                // not depend on lumnik-hub, and OrgTreeIT boots THIS module's deployment, where that
                // provider is absent. Simplify this site to the plain constructor and that test goes
                // red — the reason is a module boundary, not taste.
                throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", msg)).build());
            }
            current = orgs.findByTenantAndId(tenantId, current)
                .map(Organization::getParentOrgId)
                .orElse(null);
        }
    }

    @GET
    @Path("/tree")
    @Operation(summary = "Get org tree, optionally rooted at a specific org")
    @Transactional
    public List<OrgTreeNodeDto> tree(
            @PathParam("tenantId") Long tenantId,
            @QueryParam("root")  Long rootId,
            @QueryParam("depth") @DefaultValue("3") int depth) {
        requireTenant(tenantId);
        List<Organization> roots = rootId == null
            ? orgs.findRoots(tenantId)
            : List.of(requireOrg(tenantId, rootId));
        Set<Long> visited = new HashSet<>();
        return roots.stream().map(o -> buildNode(tenantId, o, depth, visited)).toList();
    }

    /**
     * {@code visited} is defense in depth against pre-existing corrupt data forming a cycle: a
     * properly-parented forest never revisits an id, so skipping already-visited ids here only
     * bites on data the ancestor-cycle guard in {@link #update} didn't get a chance to reject.
     */
    private OrgTreeNodeDto buildNode(Long tenantId, Organization o, int remaining, Set<Long> visited) {
        if (!visited.add(o.getId())) {
            return new OrgTreeNodeDto(
                o.getId(), o.getParentOrgId(), o.getCode(), o.getName(),
                o.getLatitude(), o.getLongitude(),
                0, true, List.of());
        }
        List<Organization> kids = orgs.findChildren(tenantId, o.getId());
        boolean leaf = kids.isEmpty();
        List<OrgTreeNodeDto> children = (remaining <= 0 || leaf)
            ? List.of()
            : kids.stream().map(k -> buildNode(tenantId, k, remaining - 1, visited)).toList();
        return new OrgTreeNodeDto(
            o.getId(), o.getParentOrgId(), o.getCode(), o.getName(),
            o.getLatitude(), o.getLongitude(),
            kids.size(), leaf, children);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Deactivate an organization")
    public Response deactivate(@PathParam("tenantId") Long tenantId, @PathParam("id") Long id) {
        Organization o = requireOrg(tenantId, id);
        o.archive();
        events.publish(tenantId, "org.archived", Map.of("id", o.getId(), "code", o.getCode()));
        return Response.noContent().build();
    }

    /**
     * Resolves new coordinates outside any transaction (the geocoder call is a blocking,
     * 8s-timeout HTTP round-trip to Nominatim). Returns {@code null} when there is nothing to
     * apply: an unusable address, an existing geocode still fresh (&lt;24h) with coordinates, or
     * the geocoder finding no match — in all three cases the caller keeps whatever coordinates
     * the org already has.
     */
    private Coords resolveGeocode(AddressInput addr, Instant existingGeocodedAt,
                                   BigDecimal existingLat, BigDecimal existingLon) {
        if (!addr.isUsable()) return null;
        if (existingGeocodedAt != null
            && existingGeocodedAt.isAfter(Instant.now().minus(Duration.ofHours(24)))
            && existingLat != null && existingLon != null) return null;
        return geocoder.resolve(addr).orElse(null);
    }

    private void requireTenant(Long tenantId) {
        Require.found(tenants, tenantId, "Tenant");
    }

    private Organization requireOrg(Long tenantId, Long id) {
        return Require.owned(orgs, id, tenantId, "Organization");
    }
}

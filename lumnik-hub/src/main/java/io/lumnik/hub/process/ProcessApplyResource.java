package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applies a kind:Process (notify) manifest: structural parse, then the honesty line at apply
 * time — the trigger table must be in the scope's SchemaCard <em>and able to fire</em>, and every
 * when-condition must be satisfiable (column exists; = value in the declared domain). A process
 * can never be declared on a condition that can never be true. Tenant from the auth context only.
 */
@Path("/api/processes")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class ProcessApplyResource {

    @Inject SchemaCardStore cards;
    @Inject ProcessManifestStore store;
    @Inject AuditRecorder audit;
    @Inject EntityRegistry entities;
    @Inject DynamicTableRegistry tables;

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record ApplyResult(String name, String table, String emit) {}
    public record ProcessInfo(String name, String table, String emit, boolean enabled) {}

    @POST
    @Path("/apply")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        JsonNode root = ApiErrors.orBadRequest(() -> parser.readTree(body));
        ProcessManifest m = ApiErrors.orBadRequest(() -> parser.parse(root));

        // C3: a key the parser does not honor used to be accepted and stored in silence — a
        // misspelled `mesage:` would have cost the decision its text with no word said.
        List<String> unknown = parser.unknownKeys(root);
        Optional<Response> unknownRefusal =
            ApplyDoorSupport.refuseUnknownKeys("Process", unknown, ProcessManifestParser.honoredSurface());
        if (unknownRefusal.isPresent()) return unknownRefusal.get();

        // May this user declare into that scope at all? BEFORE the card lookup: a 404 naming the
        // scope would otherwise tell a bound admin which scopes exist. Same order, same words as
        // ViewApplyResource — one boundary, not one per kind.
        if (!ScopeGrants.allows(m.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        // …and the NAME is the other half of the address. The upsert below conflicts on
        // (tenant_id, name) and sets scope = EXCLUDED.scope, so declaring into a scope you hold
        // under a name that belongs to one you do not would re-home that process and delete its
        // text. Same words as the check above, so the two refusals are indistinguishable (#509).
        //
        // ORDERED — read, then write — which a race can jump; the store re-asks atomically and
        // refuseUnlessApplied below is that second answer. This earlier one is kept because it
        // refuses BEFORE the 404 further down names a scope, and a caller refused for the NAME must
        // not read which scopes exist on the way out (#582).
        ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(tenant, m.name()));

        Optional<SchemaCard> card = cards.load(tenant, m.scope());
        if (card.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no schema for scope '" + m.scope() + "' — run discovery first")).build();
        }
        SchemaCard.Table table = card.get().tables().stream()
                .filter(t -> t.name().equals(m.onTable())).findFirst().orElse(null);
        if (table == null) {
            return Response.status(400).entity(ApiErrors.error(
                "spec.on.table '" + m.onTable() + "' is not in the '" + m.scope() + "' scope")).build();
        }
        if (!tables.emitsRowEvents(tenant, m.onTable())) {
            return Response.status(400).entity(ApiErrors.error(cannotFireOn(m.onTable()))).build();
        }
        ApiErrors.checkOrBadRequest(() -> {
            ProcessConditionResolver.resolve(m.when(), table);
            ProcessConditionResolver.validateMessage(m.emitMessage(), table);
            ProcessConditionResolver.validateFields(m.emitFields(), table);
        });

        // granted().orElse(null) — empty Optional means unscoped, i.e. every scope. The write's own
        // answer goes straight into the void guard, so it cannot be had and dropped.
        ApplyScopeBoundary.refuseUnlessApplied(
            store.upsert(tenant, m, body, ScopeGrants.granted().orElse(null)));
        // The store writes raw JDBC, so nothing else records the apply (#1234).
        audit.record("Process", m.name(), "APPLY", Map.of(
            "scope", m.scope(), "table", m.onTable(), "manifest_sha256", AuditRecorder.sha256(body)));
        return Response.ok(new ApplyResult(m.name(), m.onTable(), m.emitType())).build();
    }

    /**
     * Why this trigger table cannot fire. Being in the scope's card is not enough: a card also
     * advertises the {@code connector.v_} entity views {@code EntitySchemaBuilder} compiles and the
     * {@code ext.*} targets {@code HybridJsonbWriter} registers, and no row is ever written INTO
     * either — {@code data.row.changed} is staged by {@code DynamicTableWriter} alone. A manifest
     * on one of those used to pass, return a clean 200, and sit in the list silent for ever.
     *
     * <p>A fused view earns the better sentence: the entity knows the raw tables it fuses, so the
     * refusal names them and the fix is one edit away.
     */
    private String cannotFireOn(String onTable) {
        String why = "spec.on.table '" + onTable + "' cannot fire: data.row.changed is born only on"
            + " the tables a connector's row writer fills, and this is not one of them";
        String entityName = EntityViewCompiler.entityNameFromView(onTable);
        if (entityName == null) return why;
        why = "spec.on.table '" + onTable + "' is a fused entity view — data.row.changed is born"
            + " on the raw table a connector writes, never on a view, so this process could never fire";
        EntityManifest entity = entities.findReloadingIfMissing(entityName);
        if (entity == null || entity.sources() == null || entity.sources().isEmpty()) return why;
        return why + " — declare it on " + entity.sources().stream()
            .filter(Objects::nonNull)
            .map(src -> "connector." + src.table())
            .collect(Collectors.joining(" or "));
    }

    /** The stored manifest, as applied — lets the TUI's edit loop round-trip a process.
     *  No method-level @Produces: only the 200 carries yaml (set explicitly), so error
     *  responses — incl. RequireTenant's 401 Map entity — keep their JSON negotiation. */
    @GET
    @Path("/{name}/manifest")
    @Transactional
    public Response manifest(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Response refusal = refuseUnlessHeld(name, store.findScope(tenant, name));
        if (refusal != null) return refusal;
        return store.findYaml(tenant, name)
                .map(yaml -> Response.ok(yaml).type("application/yaml").build())
                // JSON via the class-level @Produces — consistent with every other error here.
                .orElseGet(() -> Response.status(404)
                        .entity(ApiErrors.error("unknown process: " + name)).build());
    }

    public record EnabledRequest(Boolean enabled) {}

    /**
     * Removes one process for good. `enabled` existed as a column since the kind was born and
     * nothing could flip it, and no door could remove a manifest either: every experimental,
     * misdeclared or accepted-but-dead process was permanent without a DBA (#487).
     *
     * <p>Scope-bound like {@link #manifest}, and for the same reason: the 404 is answered from the
     * stored scope BEFORE anything is touched, so a bound admin cannot learn that a process they
     * may not hold exists by trying to delete it.
     */
    @DELETE
    @Path("/{name}")
    @Transactional
    public Response delete(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Optional<String> scope = store.findScope(tenant, name);
        Response refusal = refuseUnlessHeld(name, scope);
        if (refusal != null) return refusal;
        // Recorded only when this call removed the row: two racing removals write one DELETE.
        if (store.delete(tenant, name)) {
            audit.record("Process", name, "DELETE", Map.of("scope", scope.get()));
        }
        return Response.noContent().build();
    }

    /**
     * Turns one process on or off. Disabling is the reversible half of {@link #delete}: the
     * manifest stays readable and re-appliable, and {@code RowEventGate} stops counting it as a
     * subscriber, so a table nothing else listens to also stops staging row events.
     */
    @PUT
    @Path("/{name}/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response setEnabled(@PathParam("name") String name, EnabledRequest body) {
        long tenant = RequireTenant.current();
        if (body == null || body.enabled() == null) {
            return Response.status(400).entity(ApiErrors.error(
                "body must carry {\"enabled\": true|false}")).build();
        }
        Optional<String> scope = store.findScope(tenant, name);
        Response refusal = refuseUnlessHeld(name, scope);
        if (refusal != null) return refusal;
        store.setEnabled(tenant, name, body.enabled());
        // Turning a process off silences what it sends: who did it is recorded (#1234).
        audit.record("Process", name, "UPDATE", Map.of("scope", scope.get(), "enabled", body.enabled()));
        return Response.ok(new EnabledResult(name, body.enabled())).build();
    }

    public record EnabledResult(String name, boolean enabled) {}

    /** 404 when this tenant has no such process ({@code scope} empty), 403 when the caller's scopes
     *  exclude it, else null. The caller reads the scope, so a write can record it afterwards. */
    private Response refuseUnlessHeld(String name, Optional<String> scope) {
        if (scope.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error("unknown process: " + name)).build();
        }
        return ScopeGrants.allows(scope.get()) ? null : ApiErrors.scopeNotGranted();
    }

    @GET
    @Transactional
    public Response list() {
        long tenant = RequireTenant.current();
        List<ProcessInfo> out = store.listByTenant(tenant).stream()
                .filter(p -> ScopeGrants.allows(p.scope()))  // a bound user must not learn the rest exist
                .map(p -> new ProcessInfo(p.name(), p.onTable(), p.emitType(), p.enabled()))
                .toList();
        return Response.ok(out).build();
    }
}

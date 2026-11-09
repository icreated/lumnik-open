package io.lumnik.hub.process;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.entity.UnreadableEntityException;
import io.lumnik.hub.sql.ManagedIndex.Spec;
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
    @Inject MatchIndexes matchIndexes;
    @Inject DynamicTableRegistry tables;

    /** {@code event_outbox.source} is VARCHAR(120) and holds {@code process:<name>}. */
    static final int MAX_NAME_LENGTH = 120 - "process:".length();

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record ApplyResult(String name, String table, String emit,
                              @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {
        public ApplyResult(String name, String table, String emit) {
            this(name, table, emit, List.of());
        }
    }
    public record ProcessInfo(String name, String table, String emit, boolean enabled) {}

    @POST
    @Path("/apply")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        JsonNode root = ApiErrors.orBadRequest(() -> parser.readTree(body));
        ProcessManifest m = ApiErrors.orBadRequest(() -> parser.parse(root));
        if (m.name().length() > MAX_NAME_LENGTH) {
            return ApiErrors.badRequest("metadata.name is at most " + MAX_NAME_LENGTH + " characters: a decision's source,"
                + " 'process:' and the name, must fit the outbox's 120 — " + m.name().length() + " given");
        }

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
            return ApiErrors.notFound("no schema for scope '" + m.scope() + "' — run discovery first");
        }
        SchemaCard.Table table = card.get().tables().stream()
                .filter(t -> t.name().equals(m.onTable())).findFirst().orElse(null);
        if (table == null) {
            return ApiErrors.badRequest(
                "spec.on.table '" + m.onTable() + "' is not in the '" + m.scope() + "' scope");
        }
        String cannotFire = cannotFire(tenant, m.onTable());
        if (cannotFire != null) {
            return ApiErrors.badRequest(cannotFire);
        }
        EntityManifest entity = matchIndexes.storedEntity(m.onTable()).orElse(null);
        List<Spec> wantedIndexes = entity == null ? List.of() : matchIndexes.wanted(entity);
        Optional<String> unindexable = matchIndexes.refusal(wantedIndexes)
            .or(() -> matchIndexes.reverseRefusal(entity == null ? List.of() : matchIndexes.reverse(entity)));
        if (unindexable.isPresent()) return ApiErrors.badRequest(unindexable.get());
        ApiErrors.checkOrBadRequest(() -> {
            ProcessConditionResolver.resolve(m.when(), table);
            ProcessConditionResolver.validateMessage(m.emitMessage(), table);
            ProcessConditionResolver.validateFields(m.emitFields(), table);
            ProcessConditionResolver.validateKey(m.emitKey(), table);
        });

        // granted().orElse(null) — empty Optional means unscoped, i.e. every scope. The write's own
        // answer goes straight into the void guard, so it cannot be had and dropped.
        ApplyScopeBoundary.refuseUnlessApplied(
            store.upsert(tenant, m, body, ScopeGrants.granted().orElse(null)));
        // The store writes raw JDBC, so nothing else records the apply (#1234).
        audit.record("Process", m.name(), "APPLY", Map.of(
            "scope", m.scope(), "table", m.onTable(), "manifest_sha256", AuditRecorder.sha256(body)));
        List<String> warnings = entity == null ? List.of() : matchIndexes.pendingAndBuild(entity, wantedIndexes);
        return Response.ok(new ApplyResult(m.name(), m.onTable(), m.emitType(), warnings)).build();
    }

    /**
     * Why this trigger cannot fire, or null when it can. Being in the scope's card is not enough: a card also
     * advertises the {@code connector.v_} entity views {@code EntityViewCompiler} compiles, and no row is ever written
     * INTO a view — {@code data.row.changed} is staged by the row writers, on the {@code connector.t_*} tables and the
     * hybrid tables (usually {@code ext.*}) a connector registered. A manifest on a name no connector registered used to
     * pass, return a clean 200, and sit in the list silent for ever.
     *
     * <p>An Entity's view is the exception that is served (#1833): a process on a {@code match:} Entity is evaluated on
     * the fused row whenever one of the Entity's sources changes. So the question moves to the sources — each must be a
     * table a row writer fills, or a change there would never reach the process.
     */
    private String cannotFire(long tenant, String onTable) {
        String entityName = EntityViewCompiler.entityNameFromView(onTable);
        if (entityName == null) {
            return tables.emitsRowEvents(tenant, onTable) ? null : cannotFireOn(onTable);
        }
        // The stored row, not this node's copy: another node may have re-applied the Entity (with joins:, or other sources)
        // since this registry loaded it, and this door decides what a process may be declared on.
        EntityManifest entity;
        try {
            entity = matchIndexes.storedEntity(onTable).orElse(null);
        } catch (UnreadableEntityException unreadable) {
            // written by another version: said as a refusal, without the parser's words (they quote the text it choked on);
            // a failure to reach the row is not caught here: it is the server's
            return "spec.on.table '" + onTable + "' is the view of an Entity whose stored definition cannot be read — apply the Entity again first";
        }
        if (entity == null || entity.sources() == null || entity.sources().isEmpty()) {
            return cannotFireOn(onTable) + " — no Entity is stored under that view";
        }
        if (!EntityProcessTargets.servable(entity)) {
            return "spec.on.table '" + onTable + "' is a resolution: Entity — a process on one is not supported yet;"
                + " declare it on a match: Entity, or on " + rawTables(entity);
        }
        for (EntityManifest.Source src : entity.sources()) {
            String table = src.fqn();
            if (!tables.emitsRowEvents(tenant, table)) {
                return "spec.on.table '" + onTable + "' fuses " + table + ", which cannot fire: data.row.changed is born only on"
                    + " the tables a connector's row writer fills, so a change there would never reach this process";
            }
        }
        for (EntityManifest.Join join : entity.joins()) {
            String table = EntityViewCompiler.SCHEMA + "." + join.table();
            if (!tables.emitsRowEvents(tenant, table)) {
                return "spec.on.table '" + onTable + "' joins " + table + ", which cannot fire: data.row.changed is born only on"
                    + " the tables a connector's row writer fills, so a change there would never re-evaluate the rows it reaches";
            }
        }
        return null;
    }

    private static String rawTables(EntityManifest entity) {
        return entity.sources().stream().filter(Objects::nonNull).map(EntityManifest.Source::fqn)
            .collect(Collectors.joining(" or "));
    }

    private static String cannotFireOn(String onTable) {
        return "spec.on.table '" + onTable + "' cannot fire: data.row.changed is born only on"
            + " the tables a connector's row writer fills (a connector.t_<name> table, or a hybrid table,"
            + " <schema>.<name>, usually ext.<name> (a REST endpoint's target or a one-shot ingest)), and this is"
            + " not one of them";
    }

    /** The stored manifest, as applied — lets the TUI's edit loop round-trip a process.
     *  Only the 200 carries yaml (set explicitly); every refusal is typed JSON by ApiErrors. */
    @GET
    @Path("/{name}/manifest")
    @Transactional
    public Response manifest(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Response refusal = refuseUnlessHeld(name, store.findScope(tenant, name));
        if (refusal != null) return refusal;
        return store.findYaml(tenant, name)
                .map(yaml -> Response.ok(yaml).type("application/yaml").build())
                .orElseGet(() -> ApiErrors.notFound("unknown process: " + name));
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
            return ApiErrors.badRequest("body must carry {\"enabled\": true|false}");
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
            return ApiErrors.notFound("unknown process: " + name);
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

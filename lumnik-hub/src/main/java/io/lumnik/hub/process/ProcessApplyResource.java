package io.lumnik.hub.process;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

/**
 * Applies a kind:Process (notify) manifest: structural parse, then the honesty line at apply
 * time — the trigger table must be in the scope's SchemaCard and every when-condition must be
 * satisfiable (column exists; = value in the declared domain). A process can never be declared
 * on a condition that can never be true. Tenant from the auth context only.
 */
@Path("/api/processes")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class ProcessApplyResource {

    @Inject SchemaCardStore cards;
    @Inject ProcessManifestStore store;

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record ApplyResult(String name, String table, String emit) {}
    public record ProcessInfo(String name, String table, String emit, boolean enabled) {}

    @POST
    @Path("/apply")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        ProcessManifest m;
        try {
            m = parser.parse(body);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }

        // C3: a key the parser does not honor used to be accepted and stored in silence — a
        // misspelled `mesage:` would have cost the decision its text with no word said.
        List<String> unknown = parser.unknownKeys(body);
        if (!unknown.isEmpty()) {
            return Response.status(400).entity(ApiErrors.error(
                "unknown key(s) in the manifest: " + String.join(", ", unknown)
                    + " — kind:Process honors " + ProcessManifestParser.honoredSurface())).build();
        }

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
        try {
            ProcessConditionResolver.resolve(m.when(), table);
            ProcessConditionResolver.validateMessage(m.emitMessage(), table);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }

        store.upsert(tenant, m, body);
        return Response.ok(new ApplyResult(m.name(), m.onTable(), m.emitType())).build();
    }

    /** The stored manifest, as applied — lets the TUI's edit loop round-trip a process.
     *  No method-level @Produces: only the 200 carries yaml (set explicitly), so error
     *  responses — incl. RequireTenant's 401 Map entity — keep their JSON negotiation. */
    @GET
    @Path("/{name}/manifest")
    @Transactional
    public Response manifest(@jakarta.ws.rs.PathParam("name") String name) {
        long tenant = RequireTenant.current();
        return store.findYaml(tenant, name)
                .map(yaml -> Response.ok(yaml).type("application/yaml").build())
                // JSON via the class-level @Produces — consistent with every other error here.
                .orElseGet(() -> Response.status(404)
                        .entity(ApiErrors.error("unknown process: " + name)).build());
    }

    @GET
    @Transactional
    public Response list() {
        long tenant = RequireTenant.current();
        List<ProcessInfo> out = store.listByTenant(tenant).stream()
                .map(p -> new ProcessInfo(p.name(), p.onTable(), p.emitType(), p.enabled()))
                .toList();
        return Response.ok(out).build();
    }
}

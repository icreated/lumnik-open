package io.lumnik.hub.workflow;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.extraction.spi.ExtractionSink;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.rag.chunk.WorkflowChunkBuilder;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a kind:Workflow manifest: structural parse, unknown-key refusal, upsert, then the
 * data-truth report — undeclared values and unobserved states surfaced, never blocking.
 * Chunks are (re)indexed here, at apply time, per tenant with the manifest's scope as tag:
 * the startup PlatformIndexer indexes as tenant 0 "system" and cannot carry tenant manifests.
 * Sink absence (open edition) is silent — the declaration stands on its own.
 */
@Path("/api/workflows")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class WorkflowApplyResource {

    private static final Logger LOG = Logger.getLogger(WorkflowApplyResource.class);

    @Inject WorkflowManifestStore store;
    @Inject AuditRecorder audit;
    @Inject WorkflowDataTruth truth;
    @Inject WorkflowChunkBuilder chunkBuilder;
    @Inject SchemaCardStore cards;
    @Inject Instance<ExtractionSink> sinks;

    private final WorkflowManifestParser parser = new WorkflowManifestParser();

    public record ApplyResult(String name, String scope, String table, String column,
                              WorkflowDataTruth.Report dataTruth) {}
    public record WorkflowInfo(String name, String table, String column, String scope, boolean enabled) {}

    // Deliberately NOT @Transactional. index() hands the chunk to the RAG sink, which takes a
    // connection from the default (JTA-enlisted) datasource and calls setAutoCommit(false) on
    // it — illegal while a transaction is active, and it fails the whole index with
    // XAER_RMERR. Only truth.check needs a transaction, and it gets its own below. The stores
    // here use the admin datasource, which is configured transactions=disabled, so they never
    // needed the ambient one.
    @POST
    @Path("/apply")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        WorkflowManifest m;
        try {
            m = parser.parse(body);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }
        List<String> unknown = parser.unknownKeys(body);
        if (!unknown.isEmpty()) {
            return Response.status(400).entity(ApiErrors.error(
                "unknown key(s) in the manifest: " + String.join(", ", unknown)
                    + " — kind:Workflow honors " + WorkflowManifestParser.honoredSurface())).build();
        }

        // The scope must be a real, discovered scope. A typo'd `scope: demoo` used to be
        // accepted in silence: 200, clean report — and a chunk tagged with a scope nobody
        // holds, so every scope-bound user never retrieves it and the chat resumes inventing
        // lifecycles. Only the SCOPE is checked: the trigger table is deliberately NOT
        // required to be in the card, because declaring a lifecycle before ingesting the data
        // is a valid order of operations (that is why `tableMissing` is a legitimate outcome).
        // 404 and this exact message, verbatim from ProcessApplyResource: two sibling apply
        // doors answering the identical condition ("this scope has no schema card yet") with
        // different status codes is the drift that makes an integrator — and lm — special-case
        // one kind for no reason. kind:Workflow is kind:Process's sibling in the same house.
        if (cards.load(tenant, m.scope()).isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no schema for scope '" + m.scope() + "' — run discovery first")).build();
        }

        // A column has exactly one lifecycle. Without this, renaming a workflow and re-applying
        // leaves the old manifest AND its chunk alive: two declared graphs over the same column,
        // both retrieved, and the chat must choose — the confident-wrong failure this feature
        // exists to prevent.
        Optional<String> owner = store.findOtherOnColumn(tenant, m.table(), m.column(), m.name());
        if (owner.isPresent()) {
            return Response.status(400).entity(ApiErrors.error(
                "column " + m.table() + "." + m.column() + " already has a declared lifecycle: "
                    + "workflow '" + owner.get() + "' — a column has exactly one lifecycle; "
                    + "re-apply under the name '" + owner.get() + "' to update it")).build();
        }

        // store.upsert / truth.check throw a bare RuntimeException on anything beyond the
        // modeled "table missing" case (e.g. a DB outage) — caught here so the caller gets a
        // stable JSON error instead of a leaked stack trace; the real exception is logged
        // server-side. Mirrors DataRowResource's convention (error message only, no stack).
        try {
            store.upsert(tenant, m, body);
            WorkflowGraph g = WorkflowGraph.from(m);
            // truth.check issues SET LOCAL, which needs an ambient transaction (see
            // TenantRlsScope); index() must NOT have one. Scoping the transaction to the check
            // alone is what separates them — see the javadoc on index().
            WorkflowDataTruth.Report report =
                QuarkusTransaction.requiringNew().call(() -> truth.check(tenant, g));
            index(tenant, g);
            // Audit P4: same rationale as ViewApplyResource — the store writes raw JDBC,
            // nothing else records the apply.
            audit.record("WorkflowManifest", m.name(), "APPLY", Map.of(
                "scope", m.scope(), "table", m.table(), "column", m.column(),
                "manifest_sha256", AuditRecorder.sha256(body)));
            return Response.ok(
                new ApplyResult(m.name(), m.scope(), m.table(), m.column(), report)).build();
        } catch (RuntimeException e) {
            // The pre-check above (findOtherOnColumn) is not a lock: two concurrent applies of
            // different names on the same column can both pass it under READ COMMITTED, and the
            // loser hits V32's uq_workflow_manifest_tenant_column constraint inside store.upsert
            // — a unique-violation (SQLSTATE 23505), wrapped once as a bare RuntimeException by
            // the store. That is the exact condition the pre-check refuses with 400, just caught
            // a beat later — so it gets the same 400 surface, not a leaked 500. Unwrapped by
            // SQLSTATE, not by message text, because the store's wrapping is free to change its
            // wording without this becoming a silent regression to 500.
            if (isUniqueViolation(e)) {
                return Response.status(400).entity(ApiErrors.error(
                    "column " + m.table() + "." + m.column() + " already has a declared lifecycle"
                        + " — a column has exactly one lifecycle; re-apply under its existing name"
                        + " to update it")).build();
            }
            LOG.errorf(e, "workflow apply failed for tenant %d, workflow %s", tenant, m.name());
            return Response.status(500).entity(ApiErrors.error(e.getMessage())).build();
        }
    }

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se
                && UNIQUE_VIOLATION_SQLSTATE.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @GET
    @Transactional
    public Response list() {
        long tenant = RequireTenant.current();
        List<WorkflowInfo> out = store.listByTenant(tenant).stream()
            .map(w -> new WorkflowInfo(w.name(), w.table(), w.column(), w.scope(), w.enabled()))
            .toList();
        return Response.ok(out).build();
    }

    /** The stored manifest, as applied — the TUI/CLI edit round-trip. Mirror of kind:Process. */
    @GET
    @Path("/{name}/manifest")
    @Transactional
    public Response manifest(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        return store.findYaml(tenant, name)
            .map(yaml -> Response.ok(yaml).type("application/yaml").build())
            .orElseGet(() -> Response.status(404)
                .entity(ApiErrors.error("unknown workflow: " + name)).build());
    }

    /**
     * Replace this workflow's chunks for this tenant; sink.index is idempotent per (tenant, source).
     *
     * <p><b>Must be called with NO ambient JTA transaction.</b> The RAG sink takes a connection
     * from the default (JTA-enlisted) datasource and calls {@code setAutoCommit(false)} on it,
     * which throws {@code XAER_RMERR: Enlisted connection used without active transaction} while
     * a transaction is active. {@code PlatformIndexer.reindex()} is not transactional, so startup
     * indexing always worked and this was invisible until a live run of the WOW act hit it — the
     * failure is swallowed below, so apply still answers 200 and only a WARN marks the corpus
     * silently missing its chunk. {@code WorkflowIndexClosedIT} pins the invariant.
     */
    private void index(long tenant, WorkflowGraph g) {
        for (ExtractionSink sink : sinks) {
            try {
                sink.index(tenant, "workflow:" + g.name(), List.of(g.scope()), chunkBuilder.build(g));
            } catch (Exception e) {
                LOG.warnf("workflow chunk indexing failed on sink %s: %s", sink.name(), e.getMessage());
            }
        }
    }
}

package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ExtractionSink;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.rag.chunk.WorkflowChunkBuilder;
import io.lumnik.hub.sql.SqlFailureText;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.inject.Instance;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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

        JsonNode root;
        WorkflowManifest m;
        try {
            root = parser.readTree(body);
            m = parser.parse(root);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
        }
        List<String> unknown = parser.unknownKeys(root);
        Optional<Response> unknownRefusal =
            ApplyDoorSupport.refuseUnknownKeys("Workflow", unknown, WorkflowManifestParser.honoredSurface());
        if (unknownRefusal.isPresent()) return unknownRefusal.get();

        // May this user declare into that scope at all? BEFORE the card lookup, because a 404
        // "no schema for scope 'x'" would otherwise tell a bound integrator which scopes exist —
        // the refusal must read the same for a real scope and an invented one. Same order, same
        // words as ViewApplyResource: one boundary, not one per kind.
        if (!ScopeGrants.allows(m.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        // …and the NAME is the other half of the address. store.upsert conflicts on
        // (tenant_id, name) and sets scope = EXCLUDED.scope, so declaring into a scope you hold
        // under a name that belongs to one you do not would re-home that lifecycle — manifest,
        // chunk and observation ledger — into a métier its owner does not hold. Same words as the
        // check above, so the two refusals are indistinguishable (#509).
        //
        // ORDERED — read, then write — which a race can jump; the store re-asks atomically and
        // refuseUnlessApplied below is that second answer. This earlier one is kept because it
        // refuses BEFORE the 404 further down names a scope and before the refusal below names
        // another métier's owner, and a caller refused for the NAME must not read either (#582).
        ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(tenant, m.name()));

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
        Optional<SchemaCard> card = cards.load(tenant, m.scope());
        if (card.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error(
                "no schema for scope '" + m.scope() + "' — run discovery first")).build();
        }

        // …and the table must not belong to a métier this caller does not hold. The scope check
        // above answers "may you declare INTO this scope"; it says nothing about which table you
        // then point at, so a scope-bound integrator could name another métier's ingest table and
        // read up to LIMIT distinct values of any column of it out of the data-truth report below.
        // The sibling door already refuses this — ViewResolver.findTable resolves the table against
        // the scope's card — and a workflow had no equivalent.
        //
        // Three cases, and only the third is refused, which is what keeps declaring-before-ingesting
        // legal: the table is in MY card (fine), in NO card (fine — it may simply not be ingested
        // yet, the `tableMissing` case), or in someone else's alone (refused). A table may appear in
        // several cards, so "mine" is checked first rather than inferred from the other query.
        if (!card.get().tableNames().contains(m.table())
                && cards.findOtherScopeAdvertising(tenant, m.scope(), m.table()).isPresent()) {
            return Response.status(400).entity(ApiErrors.error(
                "spec.on.table " + m.table() + " belongs to another métier's scope — a workflow may "
                    + "only watch a table of its own scope. Declare it in the scope that owns the "
                    + "table, or point at a table of '" + m.scope() + "'")).build();
        }

        // A column has exactly one lifecycle. Without this, renaming a workflow and re-applying
        // leaves the old manifest AND its chunk alive: two declared graphs over the same column,
        // both retrieved, and the chat must choose — the confident-wrong failure this feature
        // exists to prevent.
        Optional<WorkflowManifestStore.ColumnOwner> owner =
            store.findOtherOnColumn(tenant, m.table(), m.column(), m.name());
        if (owner.isPresent()) {
            return ApiErrors.badRequest(columnTaken(m, owner.get()));
        }

        // store.upsert / truth.check throw a bare RuntimeException on anything beyond the
        // modeled "table missing" case (e.g. a DB outage) — caught here so the caller gets a
        // stable JSON error instead of a leaked stack trace; the real exception is logged
        // server-side. Mirrors DataRowResource's convention (error message only, no stack).
        try {
            // granted().orElse(null) — empty Optional means unscoped, i.e. every scope. The
            // write's own answer goes straight into the void guard, so it cannot be had and
            // dropped. It THROWS, like its siblings, and reaches the wire intact because the
            // WebApplicationException arm below re-throws ahead of the broad catch.
            ApplyScopeBoundary.refuseUnlessApplied(
                store.upsert(tenant, m, body, ScopeGrants.granted().orElse(null)));
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
        } catch (WebApplicationException e) {
            // A refusal this door already dressed is not a store failure. WebApplicationException
            // IS a RuntimeException, so without this arm the broad catch below would swallow every
            // 403/404/400 raised inside the try and re-serve it as an anonymous 500 — the scope
            // refusal above first, and whatever a future edit adds here after it (#582).
            throw e;
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

    /**
     * The refusal for a column that already carries a lifecycle — and how much of the owner it
     * may say.
     *
     * <p>The NAME is what makes this actionable ("re-apply under that name to update it"), and it
     * is also a name out of another métier's card when the caller does not hold the owner's scope.
     * {@code WorkflowManifestStore.findOtherOnColumn} says why the lookup itself cannot be
     * scope-filtered; the consequence is that the boundary is drawn here, in the wording (#516).
     * Reaching it at all takes a table advertised in BOTH cards: the check just above refuses a
     * table that belongs only to another scope.
     *
     * <p>The foreign branch still says the column is taken and by a métier the caller does not
     * hold, because a refusal that named nothing would send an integrator to guess the name and
     * meet a 403 there instead. Naming "another métier's scope" is the same disclosure the table
     * check above already makes at this very door.
     */
    private static String columnTaken(WorkflowManifest m, WorkflowManifestStore.ColumnOwner owner) {
        String head = "column " + m.table() + "." + m.column() + " already has a declared lifecycle";
        if (!ScopeGrants.allows(owner.scope())) {
            return head + ", in a métier you do not hold — a column has exactly one lifecycle; "
                + "ask the integrator who owns that scope, or watch another column";
        }
        return head + ": workflow '" + owner.name() + "' — a column has exactly one lifecycle; "
            + "re-apply under the name '" + owner.name() + "' to update it";
    }

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private static boolean isUniqueViolation(Throwable e) {
        return UNIQUE_VIOLATION_SQLSTATE.equals(SqlFailureText.sqlStateOf(e));
    }

    @GET
    @Transactional
    public Response list() {
        long tenant = RequireTenant.current();
        List<WorkflowInfo> out = store.listByTenant(tenant).stream()
            .filter(w -> ScopeGrants.allows(w.scope()))   // a bound user must not learn the rest exist
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
        Response refusal = refuseUnlessHeld(tenant, name);
        if (refusal != null) return refusal;
        return store.findYaml(tenant, name)
            .map(yaml -> Response.ok(yaml).type("application/yaml").build())
            .orElseGet(() -> Response.status(404)
                .entity(ApiErrors.error("unknown workflow: " + name)).build());
    }

    public record EnabledRequest(Boolean enabled) {}
    public record EnabledResult(String name, boolean enabled) {}

    /**
     * Removes one workflow for good — the delete gesture #487 gave kind:Process, missing here
     * (#507). Scope-bound like {@link #manifest}: the 404 is answered from the stored scope
     * BEFORE anything is touched, so a bound integrator cannot learn that a lifecycle they may
     * not hold exists by trying to delete it.
     *
     * <p>Deliberately NOT {@code @Transactional} — same reason as {@link #apply}: {@link
     * #clearIndex} calls the sink and must run with no ambient JTA transaction (review round
     * on #507; a deleted workflow's chunk was otherwise left servable, so the chat could keep
     * answering from a lifecycle that no longer exists).
     */
    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Response refusal = refuseUnlessHeld(tenant, name);
        if (refusal != null) return refusal;
        store.delete(tenant, name);
        clearIndex(tenant, name);
        return Response.noContent().build();
    }

    /**
     * Turns one lifecycle on or off. Disabling is the reversible half of {@link #delete}: the
     * manifest stays readable and re-appliable, and {@code WorkflowRegistry}/
     * {@code WorkflowDormancySweep} already filter on {@code enabled} — this door is the hand
     * that was missing to reach the switch (#507).
     *
     * <p>Also the reversible half of {@link #clearIndex}: disabling clears the chunk the same
     * way delete does (review round on #507 — the promise that the hub stops treating this as
     * a live lifecycle must hold for what the chat can retrieve too), and enabling rebuilds it
     * from the stored manifest rather than leaving the corpus silently missing it until a future
     * re-apply. Deliberately NOT {@code @Transactional}, same reason as {@link #delete}.
     */
    @PUT
    @Path("/{name}/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setEnabled(@PathParam("name") String name, EnabledRequest body) {
        long tenant = RequireTenant.current();
        if (body == null || body.enabled() == null) {
            return Response.status(400).entity(ApiErrors.error(
                "body must carry {\"enabled\": true|false}")).build();
        }
        Response refusal = refuseUnlessHeld(tenant, name);
        if (refusal != null) return refusal;
        store.setEnabled(tenant, name, body.enabled());
        if (body.enabled()) {
            store.findYaml(tenant, name)
                .ifPresent(yaml -> index(tenant, WorkflowGraph.from(parser.parse(yaml))));
        } else {
            clearIndex(tenant, name);
        }
        return Response.ok(new EnabledResult(name, body.enabled())).build();
    }

    /**
     * Removes this workflow's chunk from every sink — the disable/delete half of {@link #index}.
     * Same no-ambient-transaction requirement, and the same per-sink catch-and-warn so one
     * sink's failure cannot block the others or the door's 200/204.
     */
    private void clearIndex(long tenant, String name) {
        for (ExtractionSink sink : sinks) {
            try {
                sink.clear(tenant, "workflow:" + name);
            } catch (Exception e) {
                LOG.warnf("workflow chunk clear failed on sink %s: %s", sink.name(), e.getMessage());
            }
        }
    }

    /** 404 when this tenant has no such workflow, 403 when the caller's scopes exclude it, else null. */
    private Response refuseUnlessHeld(long tenant, String name) {
        Optional<String> scope = store.findScope(tenant, name);
        if (scope.isEmpty()) {
            return Response.status(404).entity(ApiErrors.error("unknown workflow: " + name)).build();
        }
        return ScopeGrants.allows(scope.get()) ? null : ApiErrors.scopeNotGranted();
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

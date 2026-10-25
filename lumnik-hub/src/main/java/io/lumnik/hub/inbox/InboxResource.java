package io.lumnik.hub.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The inbox — the human surface over decision.* events (the fruit of declared kind:Process
 * attention rules). A READ of the outbox tail, not a consumer: no cursor. App state at rung 1
 * is a fold over the log: an ack is an {@code annotation.decision.acked} event, never a state
 * row. Tenant-bound, and scope-bound for a caller holding {@code scope:*} roles (#1209). History
 * is bounded by the outbox retention purge — the inbox shows what retention keeps.
 */
@Path("/api/inbox")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class InboxResource {

    private static final Logger LOG = Logger.getLogger(InboxResource.class);
    @Inject ObjectMapper mapper;   // the Quarkus-managed one: one JSON behavior app-wide

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public record InboxEntry(long id, String occurredAt, String type, String process, String message,
                             String entity, String entityKey, boolean acked) {}

    /**
     * A decision is its scope's (#1209): the scope of the kind:Process ({@code process:<name>}) or
     * kind:Workflow ({@code workflow:<name>}) that raised it, read from the manifest as it stands. So a
     * bound caller's inbox is the decisions of these sources — resolved once per request, then one
     * array test per decision walked. A decision whose manifest is gone is no source's any more: only
     * an unbound user sees it.
     */
    private static final String GRANTED_SOURCES =
        "SELECT 'process:' || name FROM core.process_manifest WHERE tenant_id = ? AND scope = ANY (?) "
        + "UNION ALL SELECT 'workflow:' || name FROM core.workflow_manifest WHERE tenant_id = ? AND scope = ANY (?)";

    /** The sources whose decisions the caller may see; null when unbound — every source. */
    private Set<String> grantedSources(long tenant) {
        Optional<Set<String>> granted = ScopeGrants.granted();
        if (granted.isEmpty()) return null;
        return Set.copyOf(Jdbc.query(adminDs, "InboxResource.grantedSources", GRANTED_SOURCES, ps -> {
            Array scopes = ps.getConnection().createArrayOf("text", granted.get().toArray());
            ps.setLong(1, tenant);
            ps.setArray(2, scopes);
            ps.setLong(3, tenant);
            ps.setArray(4, scopes);
        }, rs -> rs.getString(1)));
    }

    /** Appended to a read of {@code d}: binds the caller's sources twice ({@code bindGrants}), null when unbound. */
    private static final String IN_GRANTED_SOURCES = "AND (?::text[] IS NULL OR d.source = ANY (?::text[])) ";

    /**
     * {@code process=} and {@code type=} narrow the query, not the page (#1356): filtered after the
     * fact, a page would answer about the tenant's newest N decisions rather than what was asked.
     * {@code process} is what a decision shows as its process — a kind:Process name, or
     * {@code workflow:<name>} for a kind:Workflow's. The scope binding applies either way.
     */
    @GET
    public Response list(@QueryParam("limit") @DefaultValue("50") int limit,
                         @QueryParam("all") @DefaultValue("false") boolean all,
                         @Context UriInfo uri) {
        long tenant = RequireTenant.current();
        int lim = Math.min(Math.max(limit, 1), 200);
        MultivaluedMap<String, String> query = uri.getQueryParameters();
        String process = ApiErrors.orBadRequest(() -> ApiErrors.once(query, "process"));
        String type = ApiErrors.orBadRequest(() -> ApiErrors.once(query, "type"));
        String source = process == null ? null : sourceOf(process);
        Set<String> sources = grantedSources(tenant);
        List<InboxEntry> out = Jdbc.query(adminDs, "InboxResource.list", listSql(all),
            ps -> {
                ps.setLong(1, tenant);
                ScopeGuardedUpsert.bindGrants(ps, 2, sources);
                ps.setString(4, source);
                ps.setString(5, source);
                ps.setString(6, type);
                ps.setString(7, type);
                ps.setInt(8, lim);
            },
            this::shape);
        return Response.ok(out).build();
    }

    /**
     * The inbox read (binds: tenant, the caller's sources twice, the source filter twice, the type
     * filter twice, limit) — package-private so its plan can be pinned.
     */
    static String listSql(boolean all) {
        // "Acked" is looked up by the key ack() writes: one uq_event_outbox_dedup probe, whose one
        // row then has its type checked, so no other writer's key can ever ack a decision.
        String ackMatch = "SELECT 1 FROM core.event_outbox a WHERE a.tenant_id = d.tenant_id "
                        + "AND a.dedup_key = '" + ACK_KEY + "' || d.id AND a.event_type = '" + ACK_TYPE + "'";
        // Default fold: NOT EXISTS already guarantees every returned row is un-acked, so `acked`
        // is a constant false there — skip the redundant per-row EXISTS. Only ?all=true computes it.
        String ackedExpr = all ? "EXISTS (" + ackMatch + ")" : "false";
        return "SELECT d.id, d.event_type, d.source, d.entity, d.entity_key, d.payload, d.occurred_at, "
             + ackedExpr + " AS acked "
             + "FROM core.event_outbox d WHERE d.tenant_id = ? AND d.event_type LIKE 'decision.%' "
             + IN_GRANTED_SOURCES
             + "AND (?::text IS NULL OR d.source = ?) AND (?::text IS NULL OR d.event_type = ?) "
             + (all ? "" : "AND NOT EXISTS (" + ackMatch + ") ")
             + "ORDER BY d.id DESC LIMIT ?";
    }

    private static final String ACK_TYPE = "annotation.decision.acked";
    private static final String ACK_KEY = "ack:";   // + decision id: the ack's dedup key
    private static final String ACK_SQL =
        "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, entity, entity_key, payload, dedup_key, occurred_at, created_at) "
        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW()) "
        + "ON CONFLICT (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING";

    @POST
    @Path("/{id}/ack")
    public Response ack(@PathParam("id") long id) {
        long tenant = RequireTenant.current();
        Long userId = TenantContextFilter.currentUser();
        String by = userId != null ? String.valueOf(userId) : "unknown";

        // Verify then emit — two autocommit statements, each its own connection (they never
        // shared a transaction). Honest verification: the id must be THIS tenant's decision, in a
        // scope the caller holds; a foreign or out-of-scope id is indistinguishable from a
        // nonexistent one (no cross-tenant, no cross-scope leak).
        Set<String> sources = grantedSources(tenant);
        var decision = Jdbc.queryOne(adminDs, "InboxResource.ack.verify",
            "SELECT entity, entity_key FROM core.event_outbox d "
                + "WHERE id = ? AND tenant_id = ? AND event_type LIKE 'decision.%' " + IN_GRANTED_SOURCES,
            ps -> { ps.setLong(1, id); ps.setLong(2, tenant); ScopeGuardedUpsert.bindGrants(ps, 3, sources); },
            rs -> new String[]{rs.getString(1), rs.getString(2)});
        if (decision.isEmpty()) {
            return Response.status(404).entity(Map.of("error",
                "no decision " + id + " in your inbox")).build();
        }

        Jdbc.update(adminDs, "InboxResource.ack", ACK_SQL, ps -> {
            ps.setLong(1, tenant);
            ps.setString(2, ACK_TYPE);
            ps.setString(3, "user:" + by);
            ps.setString(4, decision.get()[0]);
            ps.setString(5, decision.get()[1]);
            ps.setString(6, mapper.writeValueAsString(Map.of("decision_id", id, "by", by)));
            ps.setString(7, ACK_KEY + id);
        });   // 0 rows on re-ack (dedup) — same honest 200 either way
        return Response.ok(Map.of("acked", true)).build();
    }

    /**
     * What a decision shows as its process, and back (#1356): a kind:Process decision's source is
     * {@code process:<name>} and shows the name; a kind:Workflow's is {@code workflow:<name>} and shows
     * as is. One rule, both directions, so the filter matches what the PROCESS column says.
     */
    private static final String PROCESS_SOURCE = "process:";

    private static String processOf(String source) {
        return source != null && source.startsWith(PROCESS_SOURCE) ? source.substring(PROCESS_SOURCE.length()) : source;
    }

    private static String sourceOf(String process) {
        return process.startsWith("workflow:") ? process : PROCESS_SOURCE + process;
    }

    /** Shape one outbox row; a non-object payload degrades to source-derived fallbacks, never a 500. */
    private InboxEntry shape(ResultSet rs) throws SQLException {
        String type = rs.getString("event_type");
        String source = rs.getString("source");
        String process = processOf(source);
        String message = "";
        String payload = rs.getString("payload");
        if (payload != null) {
            try {
                JsonNode n = mapper.readTree(payload);
                // Only a scalar overrides the fallback: asText() on an object/array node returns "",
                // which would silently erase the source-derived process / empty message.
                JsonNode pn = n.get("process");
                // ProcessConsumer writes the same name it puts in the source: the filter still matches.
                if (pn != null && pn.isValueNode()) process = pn.asText();
                JsonNode mn = n.get("message");
                if (mn != null && mn.isValueNode()) message = mn.asText();
            } catch (Exception e) {
                LOG.debugf("inbox: unparseable payload for event %s — using fallbacks", type);
            }
        }
        return new InboxEntry(
            rs.getLong("id"),
            Jdbc.instant(rs, "occurred_at").toString(),
            type, process, message,
            rs.getString("entity"), rs.getString("entity_key"),
            rs.getBoolean("acked"));
    }
}

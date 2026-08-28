package io.lumnik.hub.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.RequiresPermission;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The inbox — the human surface over decision.* events (the fruit of declared kind:Process
 * attention rules). A READ of the outbox tail, not a consumer: no cursor. App state at rung 1
 * is a fold over the log: an ack is an {@code annotation.decision.acked} event, never a state
 * row. Tenant-bound (unlike the cross-tenant SSE stream). History is bounded by the outbox
 * retention purge — the inbox shows what retention keeps.
 */
@Path("/api/inbox")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class InboxResource {

    private static final Logger LOG = Logger.getLogger(InboxResource.class);
    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public record InboxEntry(long id, String occurredAt, String type, String process, String message,
                             String entity, String entityKey, boolean acked) {}

    @GET
    public Response list(@QueryParam("limit") @DefaultValue("50") int limit,
                         @QueryParam("all") @DefaultValue("false") boolean all) {
        long tenant = RequireTenant.current();
        int lim = Math.min(Math.max(limit, 1), 200);

        List<InboxEntry> out = new ArrayList<>();
        String ackMatch = "SELECT 1 FROM core.event_outbox a WHERE a.tenant_id = d.tenant_id "
                        + "AND a.event_type = '" + ACK_TYPE + "' "
                        + "AND a.payload->>'decision_id' = d.id::text";
        // Default fold: NOT EXISTS already guarantees every returned row is un-acked, so `acked`
        // is a constant false there — skip the redundant per-row EXISTS. Only ?all=true computes it.
        String ackedExpr = all ? "EXISTS (" + ackMatch + ")" : "false";
        String sql = "SELECT d.id, d.event_type, d.source, d.entity, d.entity_key, d.payload, d.occurred_at, "
                   + ackedExpr + " AS acked "
                   + "FROM core.event_outbox d WHERE d.tenant_id = ? AND d.event_type LIKE 'decision.%' "
                   + (all ? "" : "AND NOT EXISTS (" + ackMatch + ") ")
                   + "ORDER BY d.id DESC LIMIT ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant);
            ps.setInt(2, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(shape(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("InboxResource.list: " + e.getMessage(), e);
        }
        return Response.ok(out).build();
    }

    private static final String ACK_TYPE = "annotation.decision.acked";
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

        // One connection for the whole ack: verify then emit, as a single unit of work.
        try (Connection c = adminDs.getConnection()) {
            // Honest verification: the id must be THIS tenant's decision — a foreign id is
            // indistinguishable from a nonexistent one (no cross-tenant existence leak).
            String entity;
            String entityKey;
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT entity, entity_key FROM core.event_outbox "
                   + "WHERE id = ? AND tenant_id = ? AND event_type LIKE 'decision.%'")) {
                ps.setLong(1, id);
                ps.setLong(2, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Response.status(404).entity(Map.of("error",
                            "no decision " + id + " in your inbox")).build();
                    }
                    entity = rs.getString(1);
                    entityKey = rs.getString(2);
                }
            }

            String payload = M.writeValueAsString(Map.of("decision_id", id, "by", by));
            try (PreparedStatement ps = c.prepareStatement(ACK_SQL)) {
                ps.setLong(1, tenant);
                ps.setString(2, ACK_TYPE);
                ps.setString(3, "user:" + by);
                ps.setString(4, entity);
                ps.setString(5, entityKey);
                ps.setString(6, payload);
                ps.setString(7, "ack:" + id);
                ps.executeUpdate();   // 0 rows on re-ack (dedup) — same honest 200 either way
            }
        } catch (Exception e) {
            throw new RuntimeException("InboxResource.ack: " + e.getMessage(), e);
        }
        return Response.ok(Map.of("acked", true)).build();
    }

    /** Shape one outbox row; a non-object payload degrades to source-derived fallbacks, never a 500. */
    private static InboxEntry shape(ResultSet rs) throws SQLException {
        String type = rs.getString("event_type");
        String source = rs.getString("source");
        String process = source != null && source.startsWith("process:")
            ? source.substring("process:".length()) : source;
        String message = "";
        String payload = rs.getString("payload");
        if (payload != null) {
            try {
                JsonNode n = M.readTree(payload);
                // Only a scalar overrides the fallback: asText() on an object/array node returns "",
                // which would silently erase the source-derived process / empty message.
                JsonNode pn = n.get("process");
                if (pn != null && pn.isValueNode()) process = pn.asText();
                JsonNode mn = n.get("message");
                if (mn != null && mn.isValueNode()) message = mn.asText();
            } catch (Exception e) {
                LOG.debugf("inbox: unparseable payload for event %s — using fallbacks", type);
            }
        }
        return new InboxEntry(
            rs.getLong("id"),
            rs.getTimestamp("occurred_at").toInstant().toString(),
            type, process, message,
            rs.getString("entity"), rs.getString("entity_key"),
            rs.getBoolean("acked"));
    }
}

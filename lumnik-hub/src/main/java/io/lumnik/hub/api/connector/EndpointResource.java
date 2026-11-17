package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.rest.runtime.RestPreflight;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hibernate.Session;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

@Path("/api/platform/connectors/{cid}/endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EndpointResource {

    @Inject ConnectorRepository connRepo;
    @Inject ConnectorEndpointRepository epRepo;
    @Inject ConnectorCursorEntityRepository cursorRepo;
    @Inject EntityManager em;
    @Inject RestPreflight preflight;
    @Inject ObjectMapper json;

    /**
     * The refusal for a non-hybrid endpoint, worded for the integrator (#1181): never the config key
     * or the internal column, and the gesture that does remove the data (connector delete).
     */
    static final String NOT_PURGEABLE_ON_ITS_OWN =
        "This endpoint writes to a connector table whose rows are not tracked per endpoint, so it "
        + "cannot be purged on its own. Deleting the connector removes its data, along with its run history.";

    @GET
    @Transactional
    public Response list(@PathParam("cid") UUID cid) {
        if (ConnectorScope.held(connRepo, cid) == null) return ApiErrors.notFound("connector " + cid + " not found");
        List<EndpointDto> dtos = epRepo.listAllByConnector(cid).stream()
            .map(EndpointDto::from)
            .toList();
        return Response.ok(dtos).build();
    }

    @GET
    @Path("{eid}")
    @Transactional
    public Response get(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);
        return Response.ok(EndpointDto.from(ep)).build();
    }

    @PUT
    @Path("{eid}")
    @Transactional
    public Response update(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid,
                           EndpointDto body) {
        ConnectorEndpoint ep = heldEndpoint(cid, eid);
        if (ep == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);

        // The sibling of ConnectorAdminResource: this door persisted config with no validation.
        // A jdbc table's rename lives here, and a rename INTO a hub system column kills the run
        // (JdbcConnectorType.validateRename) — refuse it at write time, same rule, same shape.
        if (body.config() != null) {
            // and a local path whose form the run refuses (#2001): StoredConfigRules, judged in an endpoint's shape
            Response bad = StoredConfigRules.endpointRefusal(json.valueToTree(body.config())).map(ApiErrors::badRequest).orElse(null);
            if (bad != null) return bad;
        }

        if (body.method() != null) ep.setMethod(body.method());
        if (body.path() != null) ep.setPath(InlineCredentials.restoredPath(body.path(), ep.getPath(), false));   // #2003
        if (body.schedule() != null) ep.setSchedule(body.schedule());
        ep.setEnabled(body.enabled());
        if (body.config() != null) ep.setConfig(InlineCredentials.restored(body.config(), ep.getConfig()));   // #2003

        return Response.ok(EndpointDto.from(ep)).build();
    }

    @DELETE
    @Path("{eid}")
    @Transactional
    public Response delete(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = heldEndpoint(cid, eid);
        if (ep == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);
        ep.softDelete();
        return Response.status(204).build();
    }

    /**
     * Pre-flight: send the one request a run would send, write nothing, report what came back.
     *
     * <p>The two rows are read inside a transaction of their own — the tenant is bound with SET
     * LOCAL, which is transaction-scoped — and not the method's, so the request to the source, up
     * to the HTTP client's timeout, holds no connection. Same shape as RestDiscoveryResource#probe.
     */
    @POST
    @Path("{eid}/test")
    @Consumes(MediaType.WILDCARD)
    public Response test(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        record Found(Connector conn, ConnectorEndpoint ep) {}
        Found f = QuarkusTransaction.requiringNew().call(() -> {
            Connector conn = ConnectorScope.held(connRepo, cid);
            ConnectorEndpoint ep = endpointOf(conn, eid);
            return ep == null ? null : new Found(conn, ep);
        });
        if (f == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);

        return Response.ok(preflight.check(f.conn(), f.ep())).build();
    }

    /** Clear the stored cursor for this endpoint so the next run is a full re-sync. */
    @POST
    @Path("{eid}/reset-cursor")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response resetCursor(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = heldEndpoint(cid, eid);
        if (ep == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);

        // Cursor entity keyed by connectorId; for endpoint-level cursors we store endpoint id
        var cursor = cursorRepo.findById(ep.getId());
        int deleted = 0;
        if (cursor != null) {
            cursorRepo.delete(cursor);
            deleted = 1;
        }
        return Response.ok(Map.of("deleted", deleted)).build();
    }

    /**
     * Hard-delete this endpoint's rows from its hybrid target (ext.* or connector.&lt;hybrid&gt;).
     * {@code dryRun=true} runs the same checks and refusals and counts the rows instead, so the CLI
     * can refuse, or state the count, before it asks for a confirmation.
     */
    @POST
    @Path("{eid}/purge")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response purge(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid,
                          @QueryParam("dryRun") boolean dryRun) {
        ConnectorEndpoint ep = heldEndpoint(cid, eid);
        if (ep == null) return ApiErrors.notFound("endpoint " + eid + " not found under connector " + cid);

        Map<String, Object> cfg = ep.getConfig();
        Object targetCfg = cfg != null ? cfg.get("target") : null;
        String table = null;
        if (targetCfg instanceof Map<?, ?> t) {
            Object tbl = t.get("table");
            if (tbl instanceof String s) table = s;
        }

        // No hybrid target: the usual JDBC/CSV/hello-world endpoint, whose rows land in a
        // connector.t_* table the config never names.
        if (table == null) return ApiErrors.badRequest(NOT_PURGEABLE_ON_ITS_OWN);

        // Bound the blast radius with the writer's own rule (#1989): a hybrid target is ext.<name> or
        // connector.<name>, so a mis-set target.table can never DELETE from identity/core/rag/platform.
        // Each part is still quoted (doubling embedded quotes): injection-safe by construction too.
        if (HybridJsonbSpec.targetProblem(table).isPresent()) {
            return ApiErrors.badRequest("invalid or out-of-scope table: " + table);
        }
        String[] parts = table.split("\\.", 2);
        String fqn = quoteIdent(parts[0]) + "." + quoteIdent(parts[1]);

        // Purge is by endpoint, and only from a table the hybrid writer made and recorded (#1989, #2010).
        // The schema rule gates by SCHEMA, but under connector sit the hub's own tables, and
        // connector_run carries an endpoint_id too; "is a hybrid table" is a per-TABLE property, so
        // check it here and fail cleanly (400). A table that does not exist yet — a hybrid endpoint that
        // has never run — has nothing to purge. The catalog, not information_schema: that one hides a
        // table lumnik_app has no privilege on, a false "0 deleted" (EndpointPurgeRlsIT).
        boolean exists = (Boolean) em.createNativeQuery("SELECT to_regclass(CAST(? AS text)) IS NOT NULL")
            .setParameter(1, fqn)
            .getSingleResult();
        if (!exists) return purged(0, table, dryRun);
        if (!em.unwrap(Session.class).doReturningWork(c -> DynamicTableRegistry.ownsHybridTable(c, parts[0], parts[1]))) {
            return ApiErrors.badRequest(NOT_PURGEABLE_ON_ITS_OWN);
        }

        long rows = dryRun
            ? ((Number) em.createNativeQuery("SELECT count(*) FROM " + fqn + " WHERE endpoint_id = ?")
                .setParameter(1, ep.getId()).getSingleResult()).longValue()
            : em.createNativeQuery("DELETE FROM " + fqn + " WHERE endpoint_id = ?")
                .setParameter(1, ep.getId()).executeUpdate();
        return purged(rows, table, dryRun);
    }

    private static Response purged(long rows, String table, boolean dryRun) {
        return Response.ok(Map.of("deleted", rows, "table", table, "dryRun", dryRun)).build();
    }

    private ConnectorEndpoint resolveEndpoint(UUID cid, UUID eid) {
        return endpointOf(ConnectorScope.held(connRepo, cid), eid);
    }

    /**
     * {@link #resolveEndpoint} for a door that writes the endpoint or its rows: the connector its
     * scope decision is taken on stays held until that write commits (#1217).
     */
    private ConnectorEndpoint heldEndpoint(UUID cid, UUID eid) {
        return endpointOf(ConnectorScope.heldForUpdate(connRepo, cid), eid);
    }

    /**
     * The same resolution for the one caller that needs the connector itself — the pre-flight sends
     * a request built from its config. Split rather than re-read: {@code resolveEndpoint} was
     * fetching the connector, discarding it, and leaving {@code test} to fetch it again.
     */
    private ConnectorEndpoint endpointOf(Connector conn, UUID eid) {
        if (conn == null) return null;
        ConnectorEndpoint ep = epRepo.findById(eid);
        if (ep == null || ep.isDeleted() || !conn.getId().equals(ep.getConnectorId())) return null;
        return ep;
    }
}

package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
    @Inject RestHttpClient http;
    @Inject AuthProviderRegistry authReg;

    /** Schemas dynamic ingestion writes to — the only ones an endpoint purge may target. */
    private static final java.util.Set<String> PURGEABLE_SCHEMAS = java.util.Set.of("connector", "ext");

    @GET
    @Transactional
    public Response list(@PathParam("cid") UUID cid) {
        if (connRepo.findById(cid) == null) return Response.status(404).build();
        List<EndpointDto> dtos = epRepo.listByConnector(cid).stream()
            .map(EndpointDto::from)
            .toList();
        return Response.ok(dtos).build();
    }

    @GET
    @Path("{eid}")
    @Transactional
    public Response get(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();
        return Response.ok(EndpointDto.from(ep)).build();
    }

    @PUT
    @Path("{eid}")
    @Transactional
    public Response update(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid,
                           EndpointDto body) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();

        if (body.method() != null) ep.setMethod(body.method());
        if (body.path() != null) ep.setPath(body.path());
        if (body.schedule() != null) ep.setSchedule(body.schedule());
        ep.setEnabled(body.enabled());
        if (body.config() != null) ep.setConfig(body.config());

        return Response.ok(EndpointDto.from(ep)).build();
    }

    @DELETE
    @Path("{eid}")
    @Transactional
    public Response delete(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();
        ep.softDelete();
        return Response.status(204).build();
    }

    /** Dry-run probe: execute one GET to the endpoint and return status + sample. */
    @POST
    @Path("{eid}/test")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response test(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) throws Exception {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();

        Connector conn = connRepo.findById(cid);
        String baseUrl = (String) conn.getConfig().getOrDefault("base_url", "");

        // Build auth from connector config
        @SuppressWarnings("unchecked")
        Map<String, String> authCfg = (Map<String, String>) conn.getConfig().get("auth");
        AuthSpec authSpec = authCfg != null && authCfg.containsKey("kind")
            ? new AuthSpec(authCfg.get("kind"), authCfg, List.of())
            : new AuthSpec("bearer", Map.of("token_value", ""), List.of());

        var authProvider = authReg.build(authSpec);
        RestRequest req = authProvider.apply(RestRequest.get(baseUrl + ep.getPath()));
        RestResponse resp = http.execute(req);

        return Response.ok(Map.of(
            "status", resp.status(),
            "ok", resp.status() >= 200 && resp.status() < 300,
            "sample", resp.body() != null
                      ? resp.body().substring(0, Math.min(500, resp.body().length()))
                      : ""
        )).build();
    }

    /** Clear the stored cursor for this endpoint so the next run is a full re-sync. */
    @POST
    @Path("{eid}/reset-cursor")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response resetCursor(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();

        // Cursor entity keyed by connectorId; for endpoint-level cursors we store endpoint id
        var cursor = cursorRepo.findById(ep.getId());
        int deleted = 0;
        if (cursor != null) {
            cursorRepo.delete(cursor);
            deleted = 1;
        }
        return Response.ok(Map.of("deleted", deleted)).build();
    }

    /** Hard-delete rows in the ext.* table written by this endpoint. */
    @POST
    @Path("{eid}/purge")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response purge(@PathParam("cid") UUID cid, @PathParam("eid") UUID eid) {
        ConnectorEndpoint ep = resolveEndpoint(cid, eid);
        if (ep == null) return Response.status(404).build();

        Map<String, Object> cfg = ep.getConfig();
        Object targetCfg = cfg != null ? cfg.get("target") : null;
        String table = null;
        if (targetCfg instanceof Map<?, ?> t) {
            Object tbl = t.get("table");
            if (tbl instanceof String s) table = s;
        }

        if (table == null) {
            return Response.status(400)
                .entity(Map.of("error", "no target.table found in endpoint config"))
                .build();
        }

        // Bound the blast radius: dynamic ingestion only ever writes to the `connector` (t_*)
        // and `ext` schemas, so purge must target one of those — a mis-set target.table can
        // never DELETE from identity/core/rag/platform. Quote each part (doubling embedded
        // quotes) so the identifier is injection-safe by construction, not just by the allowlist.
        // Enforce the schema.table SHAPE only (known schema + a non-blank single identifier). Safety
        // is the escaping SqlIdentifiers.quoteIdent, NOT a character allowlist — an allowlist wrongly
        // rejects valid Postgres identifiers (e.g. names containing '-').
        String[] parts = table.split("\\.", 2);
        if (parts.length != 2 || !PURGEABLE_SCHEMAS.contains(parts[0])
                || parts[1].isBlank() || parts[1].contains(".")) {
            return Response.status(400)
                .entity(Map.of("error", "invalid or out-of-scope table: " + table))
                .build();
        }
        String fqn = quoteIdent(parts[0]) + "." + quoteIdent(parts[1]);

        // Native query: delete all rows for this endpoint
        int rows = em.createNativeQuery(
            "DELETE FROM " + fqn + " WHERE _endpoint_id = ?")
            .setParameter(1, ep.getId())
            .executeUpdate();

        return Response.ok(Map.of("deleted", rows, "table", table)).build();
    }

    private ConnectorEndpoint resolveEndpoint(UUID cid, UUID eid) {
        if (connRepo.findById(cid) == null) return null;
        ConnectorEndpoint ep = epRepo.findById(eid);
        if (ep == null || ep.isDeleted() || !cid.equals(ep.getConnectorId())) return null;
        return ep;
    }
}

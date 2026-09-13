package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.api.connector.handler.ApplyResult;
import io.lumnik.hub.api.connector.handler.ApplyUpsert;
import io.lumnik.hub.api.connector.handler.ConnectorApplyHandler;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.validator.ConnectorValidationService;
import io.lumnik.hub.validator.ValidationResult;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/platform/connectors")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectorApplyResource {

    private static final YAMLMapper YAML = new YAMLMapper();   // no managed YAML mapper exists
    @Inject ObjectMapper json;   // the Quarkus-managed one: one JSON behavior app-wide
    private static final String METADATA = "metadata";
    private static final String TENANT = "tenant";

    @Inject ConnectorValidationService validationService;
    @Inject Instance<ConnectorApplyHandler> handlers;
    @Inject ConnectorRepository connectors;

    @POST
    @Path("/apply")
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) throws Exception {
        long tenant = RequireTenant.current();
        if (body == null || body.isBlank()) {
            return Response.status(400).entity(ApiErrors.error("empty body")).build();
        }
        JsonNode doc = body.trim().startsWith("{") ? json.readTree(body) : YAML.readTree(body);

        // metadata.tenant may still be declared (documented) but can no longer CHOOSE the
        // tenant: it must match the authenticated one — never a silent cross-tenant apply.
        if (doc.path(METADATA).has(TENANT)
                && doc.path(METADATA).path(TENANT).asLong() != tenant) {
            return Response.status(400).entity(ApiErrors.error(
                "manifest metadata.tenant (" + doc.path(METADATA).path(TENANT).asLong()
                    + ") does not match the authenticated tenant (" + tenant + ")")).build();
        }

        // A WRITE door asks allowsAll, not allowsAny — the same gate EntityApplyResource has, on the
        // door that had none. Reads accept an overlap; publishing into a list you only partly hold
        // widens the scopes you do not, and a scopeless connector is not a bound user's to create:
        // they could never see it. Before validation, so a refused caller learns nothing else.
        if (!ScopeGrants.allowsAll(ApplyUpsert.scopes(doc.path(METADATA)))) {
            return ApiErrors.scopeNotGranted();
        }

        // …and the NAME is the other half of the address. ApplyUpsert.connector finds this tenant's
        // connector by (tenantId, name) and overwrites its tags, schedule, window and endpoints, so
        // a caller who holds every scope they DECLARE can still take over a connector that lives in
        // scopes they do not — or narrow it out of one by re-applying a subset. Same words as the
        // check above, and before validation for the same reason (#509).
        ApplyScopeBoundary.refuseUnlessCallerHolds(
            connectors.scopesOf(tenant, doc.path(METADATA).path("name").asText()));

        ValidationResult vr = validationService.validate(doc);
        if (!vr.isValid()) return Response.status(400).entity(vr).build();

        String kind = doc.path(METADATA).path("connector_type").asText();
        ConnectorApplyHandler handler = findHandler(kind);
        if (handler == null) {
            return Response.status(400).entity(ApiErrors.error(
                "no apply handler for kind: " + kind)).build();
        }

        ApplyResult result = handler.apply(doc, tenant);
        // The advisories the validator produced above ride the success body: on this path they
        // have no other way out, and the one they warn about most — no scopes, so nothing the
        // connector ingests is askable in any métier — is silent everywhere else (#531).
        return Response.ok(result.withWarnings(vr.warnings())).build();
    }

    private ConnectorApplyHandler findHandler(String kind) {
        for (ConnectorApplyHandler h : handlers) {
            if (h.kind().equals(kind)) return h;
        }
        return null;
    }
}

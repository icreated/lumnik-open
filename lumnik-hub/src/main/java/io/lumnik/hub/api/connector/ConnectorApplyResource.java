package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.connector.handler.ApplyResult;
import io.lumnik.hub.api.connector.handler.ConnectorApplyHandler;
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

        ValidationResult vr = validationService.validate(doc);
        if (!vr.isValid()) return Response.status(400).entity(vr).build();

        String kind = doc.path(METADATA).path("connector_type").asText();
        ConnectorApplyHandler handler = findHandler(kind);
        if (handler == null) {
            return Response.status(400).entity(ApiErrors.error(
                "no apply handler for kind: " + kind)).build();
        }

        ApplyResult result = handler.apply(doc, tenant);
        return Response.ok(result).build();
    }

    private ConnectorApplyHandler findHandler(String kind) {
        for (ConnectorApplyHandler h : handlers) {
            if (h.kind().equals(kind)) return h;
        }
        return null;
    }
}

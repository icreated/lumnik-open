package io.lumnik.hub.api.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.manifest.HubYaml;
import io.lumnik.hub.validator.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

@Path("/api/platform/validator")
public class ConnectorValidatorResource {

    /** The apply door's reader (#1489): SnakeYAML read YAML 1.1, so validate and apply could disagree. */
    private static final YAMLMapper YAML = HubYaml.mapper();

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper json;

    @POST
    @Path("/validate")
    @Consumes({ MediaType.APPLICATION_JSON, "application/yaml", "application/x-yaml", "text/yaml" })
    @Produces(MediaType.APPLICATION_JSON)
    public Response validate(String body, @HeaderParam("Content-Type") String contentType) {
        JsonNode manifest;
        try {
            manifest = (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("yaml"))   // a media type has no case
                ? YAML.readTree(body)
                : json.readTree(body);
        } catch (Exception e) {
            return Response.ok(new ValidationResult(
                List.of(new ValidationError("$", ValidationError.Severity.ERROR,
                    "could not parse manifest: " + e.getMessage(), List.of())),
                List.of())).build();
        }
        return Response.ok(service.validate(manifest)).build();
    }

    @GET
    @Path("/describe/{kind}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response describe(@PathParam("kind") String kind) {
        return service.configDefFor(kind)
            .map(def -> Response.ok(ConfigDefSerializer.toJson(def)).build())
            .orElseGet(() -> Response.status(404).entity(Map.of(
                "error", "unknown connector type: " + kind,
                "available", service.availableKinds()
            )).build());
    }
}

package io.lumnik.hub.api.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

@Path("/api/platform/validator")
public class ConnectorValidatorResource {

    @Inject ConnectorValidationService service;
    @Inject ObjectMapper json;

    @POST
    @Path("/validate")
    @Consumes({ MediaType.APPLICATION_JSON, "application/yaml", "application/x-yaml", "text/yaml" })
    @Produces(MediaType.APPLICATION_JSON)
    public Response validate(String body, @HeaderParam("Content-Type") String contentType) {
        JsonNode manifest;
        try {
            manifest = (contentType != null && contentType.contains("yaml"))
                ? json.valueToTree(new Yaml().load(body))
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

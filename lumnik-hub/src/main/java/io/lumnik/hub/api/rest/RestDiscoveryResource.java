package io.lumnik.hub.api.rest;

import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.discovery.OpenApiImporter;
import io.lumnik.hub.connector.rest.discovery.RestProber;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/platform/sources/rest")
@Produces(MediaType.TEXT_PLAIN)
@Consumes(MediaType.APPLICATION_JSON)
public class RestDiscoveryResource {

    @Inject OpenApiImporter importer;
    @Inject RestProber prober;

    /**
     * Generate a connector manifest YAML from an OpenAPI spec URL.
     * Body: { "url": "...", "name": "...", "tags": ["..."] }
     */
    @POST
    @Path("/from-openapi")
    public Response fromOpenApi(Map<String, Object> body) {
        String url = (String) body.get("url");
        String name = (String) body.get("name");
        if (url == null || url.isBlank()) {
            return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "url is required"))
                .build();
        }
        if (name == null || name.isBlank()) name = "connector";

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.getOrDefault("tags", List.of());

        String yaml = importer.importFromUrl(url, name, tags);
        return Response.ok(yaml).build();
    }

    /**
     * Probe a REST endpoint and return a manifest YAML draft.
     * Body: { "baseUrl": "...", "endpointPath": "...", "name": "...", "scope": "...", "token": "..." }
     */
    @POST
    @Path("/probe")
    public Response probe(Map<String, Object> body) throws Exception {
        String baseUrl = (String) body.get("baseUrl");
        String endpointPath = (String) body.get("endpointPath");
        if (baseUrl == null || baseUrl.isBlank()) {
            return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "baseUrl is required"))
                .build();
        }
        if (endpointPath == null || endpointPath.isBlank()) endpointPath = "/";

        String name  = (String) body.getOrDefault("name", "probe");
        String scope = (String) body.getOrDefault("scope", "system");

        // Build auth spec from body: caller may pass a 'token' or a full 'auth' block
        AuthSpec authSpec;
        @SuppressWarnings("unchecked")
        Map<String, Object> authBlock = (Map<String, Object>) body.get("auth");
        if (authBlock != null) {
            Map<String, String> cfg = new HashMap<>();
            authBlock.forEach((k, v) -> cfg.put(k, String.valueOf(v)));
            String kind = cfg.getOrDefault("kind", "bearer");
            cfg.remove("kind");
            authSpec = new AuthSpec(kind, cfg, List.of());
        } else {
            String token = (String) body.getOrDefault("token", "");
            authSpec = new AuthSpec("bearer", Map.of("token_value", token), List.of());
        }

        String yaml = prober.probe(baseUrl, endpointPath, authSpec, name, scope);
        return Response.ok(yaml).build();
    }
}

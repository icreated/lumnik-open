package io.lumnik.hub.view;

import io.lumnik.common.ratelimit.RateLimited;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a kind:View manifest from a natural-language description: the model selects, the
 * existing gate validates (ViewGenerateAgent), the code assembles. Success is text the user
 * keeps (`application/yaml`, ready for `lm view -f`); an abstention or a post-retry failure is
 * an honest 400. Tenant comes only from the auth context (never a client header).
 */
@Path("/api/views")
// Declares BOTH: negotiation checks Accept against @Produces BEFORE invoking the method —
// JSON-only 406'd the real client's `Accept: application/yaml` (caught live). Every response
// still sets its type explicitly: errors JSON (incl. the upstream 401, whose Map negotiates
// JSON with the client's json-first Accept), success application/yaml.
@Produces({MediaType.APPLICATION_JSON, "application/yaml"})
@RequiresPermission("lm_user")
@RateLimited("view-generate")
public class ViewGenerateResource {

    @Inject SchemaCardStore cards;
    @Inject ViewGenerateAgent agent;

    public record GenerateRequest(String scope, String description) {}

    // No @Transactional: no data read/write here — SchemaCardStore manages its own admin
    // connection, and a JTA tx would only sit open across the (slow, up to 2×) model calls.
    @POST
    @Path("/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response generate(GenerateRequest req) {
        long tenant = RequireTenant.current();
        if (req == null || req.scope() == null || req.scope().isBlank()
                || req.description() == null || req.description().isBlank()) {
            return Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(ApiErrors.error("scope and description are required")).build();
        }
        String scope = req.scope().strip();

        if (!ScopeGrants.allows(scope)) {
            return ApiErrors.scopeNotGranted();
        }

        Optional<SchemaCard> card = cards.load(tenant, scope);
        if (card.isEmpty()) {
            return Response.status(404).type(MediaType.APPLICATION_JSON).entity(ApiErrors.error(
                "no schema for scope '" + scope + "' — run discovery first")).build();
        }

        ViewGenerateAgent.Outcome out = agent.generate(card.get(), scope, req.description());
        return switch (out.status()) {
            case OK -> Response.ok(out.yaml(), "application/yaml").build();
            case ABSTAINED -> Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(ApiErrors.error("generation abstained: " + out.message())).build();
            case FAILED -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(ApiErrors.KEY, "generation failed: " + out.message());
                body.put("raw", out.raw());
                yield Response.status(400).type(MediaType.APPLICATION_JSON).entity(body).build();
            }
        };
    }
}

package io.lumnik.hub.api.rest;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.connector.ConnectorScope;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.api.helpers.Configs;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.rest.discovery.OpenApiImporter;
import io.lumnik.hub.connector.rest.discovery.RestProber;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/platform/sources/rest")
@Produces(MediaType.TEXT_PLAIN)
@Consumes(MediaType.APPLICATION_JSON)
public class RestDiscoveryResource {

    private static final Logger LOG = Logger.getLogger(RestDiscoveryResource.class);

    @Inject OpenApiImporter importer;
    @Inject RestProber prober;
    @Inject ConnectorRepository connRepo;

    /**
     * Generate a connector manifest YAML from an OpenAPI spec URL.
     * Body: { "url": "...", "name": "...", "tags": ["..."] }
     */
    @POST
    @Path("/from-openapi")
    public Response fromOpenApi(Map<String, Object> body) {
        String url = Configs.string(body, "url").orElse(null);
        if (url == null) return refuse(400, "url is required");
        // A url that is not a URL is the CALLER's mistake, and a different diagnosis from a URL
        // that holds no spec: fix your command, versus go look at the source. Same structural rule
        // the manifest door applies to base_url, and it names the reason, never the value.
        var notAUrl = Urls.whyNotAbsolute(url);
        if (notAUrl.isPresent()) return refuse(400, "'url' " + notAUrl.get());
        String name = Configs.string(body, "name").orElse("connector");

        List<String> tags = ApiErrors.orBadRequest(() -> stringList(body, "tags"));

        try {
            return Response.ok(importer.importFromUrl(url, name, tags)).build();
        } catch (ConnectorException unreadable) {
            // A URL that holds no spec is the upstream's problem, not the hub's, and it used to
            // reach the operator as a bare 500 plus an error id. The importer names it; this only
            // dresses it — same gesture as the probe below, and as JdbcSourceResource#discover.
            // Narrow on purpose: a defect in our own manifest build must keep saying 500.
            LOG.warnf("OpenAPI import refused: %s", unreadable.getMessage());   // already redacted
            return refuse(502, unreadable.getMessage());
        }
    }

    /**
     * Probe one path of an existing connector and return an endpoint manifest fragment.
     * Body: { "connectorId": "...", "endpointPath": "...", "name": "..." }
     *
     * <p>The connector is the only way in; why, and what the body used to carry instead, is written
     * where the rule lives — {@link RestProber#probe(Connector, String, String)}.
     */
    @POST
    @Path("/probe")
    public Response probe(Map<String, Object> body) throws Exception {
        String connectorId = Configs.string(body, "connectorId").orElse(null);
        if (connectorId == null) return refuse(400, "connectorId is required");

        UUID cid = ApiErrors.uuidOrRefuse(connectorId);
        // Read inside a transaction of its own, and only the read: the tenant is bound with
        // SET LOCAL, which is transaction-scoped, so an unwrapped lookup finds nothing on an
        // RLS-enforced hub. Its own and not the method's, so the probe below — up to the
        // HttpClient's 60s — holds no pooled connection. Same shape as IntegrationResource#test.
        // The connector rule of the by-id doors (#1212): another scope's connector is the uniform 403.
        Connector c = QuarkusTransaction.requiringNew().call(() -> ConnectorScope.held(connRepo, cid));
        if (c == null) return ApiErrors.notFound("connector " + cid + " not found");

        String endpointPath = Configs.string(body, "endpointPath").orElse("/");

        // The connector's own name by default, so the drafted `ext.<name>_<path>` table belongs to
        // the connector the fragment will be pasted into.
        String name = Configs.string(body, "name").orElse(c.getName());

        try {
            return Response.ok(prober.probe(c, endpointPath, name)).build();
        } catch (ConfigValidationException | AuthConfigException misconfigured) {
            // The connector cannot address a source at all, or its credential does not resolve.
            // Neither is the upstream's fault, so neither is a 502: the operator has to fix the row
            // or set the secret. `AuthConfigException` extends RuntimeException, not
            // ConnectorException, so without naming it here it would sail past every catch below
            // and land as a 500. `ConfigValidationException` is caught before its supertype.
            return refuse(400, "connector " + c.getName() + ": " + misconfigured.getMessage());
        } catch (ConnectorException refused) {
            // ResponseStatusGuard already named the status and bounded an excerpt of the body —
            // TransientFailureException extends this, so one catch covers the 429 and the 401.
            // Throwing it on turned a diagnosable refusal into Internal Server Error + an error id.
            // The message carries a bounded excerpt of the UPSTREAM BODY (ResponseStatusGuard), which
            // can hold a token or personal data. It is returned to the caller who asked for it and
            // goes no further: an application log is broader, longer-lived, and shipped elsewhere.
            LOG.warnf("REST probe refused for connector %s path %s (detail returned to the caller)",
                cid, Urls.forDisplay(endpointPath));
            return refuse(502, refused.getMessage());
        } catch (IOException unreachable) {
            // A source nobody answers on is the upstream's problem too. Named narrowly rather than
            // by a blanket catch: a NullPointerException in the inference is a hub defect and must
            // keep saying 500 rather than be dressed up as the customer's network.
            // forDisplay on the path too: it is caller input and legitimately carries a query, so
            // it can carry a token. Both warn lines in this method, not only one of them.
            // The kind, never the exception: its message can quote the source URL (#1164).
            LOG.warnf("REST probe could not reach connector %s path %s: %s",
                cid, Urls.forDisplay(endpointPath), OutboundFailure.describe(unreachable));
            return refuse(502, unreachable(unreachable));
        }
    }

    /**
     * The 502 body of a source nobody answered on: the failure's kind and class, never its
     * message, which can quote the integrator's URL — userinfo, a token in the path or the query
     * (#1164). Never empty either: a ConnectException's message can be null.
     */
    static String unreachable(IOException unreachable) {
        return "could not reach the source: " + OutboundFailure.describe(unreachable);
    }

    /**
     * A list of strings off the request body, or a refusal naming the field.
     *
     * <p>Request bodies arrive as {@code Map<String, Object>}: a blind {@code (List<String>)} cast
     * passes erasure and then throws deep inside {@code String.join}, and {@code "tags":"internal"}
     * throws at the cast itself — both as {@code Internal Server Error}. A caller sending the wrong
     * type made a mistake it can fix; it is a 400, and the field has to be named for that to be
     * true. The string fields go through {@link Configs#string}, the config map's own
     * non-String-safe reader.
     */
    private static List<String> stringList(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) throw new IllegalArgumentException("'" + key + "' must be a list of strings");
        List<String> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof String s)) throw new IllegalArgumentException("'" + key + "' must be a list of strings");
            out.add(s);
        }
        return out;
    }

    /** The JSON refusal, in the class's own YAML-producing context — the type must be explicit. */
    private static Response refuse(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error(message)).build();
    }
}

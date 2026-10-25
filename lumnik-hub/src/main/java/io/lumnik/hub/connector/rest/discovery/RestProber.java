package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.EffectiveConfig;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.DottedPath;
import io.lumnik.hub.connector.rest.RequestHeaders;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.ResponseStatusGuard;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.runtime.RestConnectorType;
import io.lumnik.hub.connector.rest.runtime.RestEndpointReader;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class RestProber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject RestHttpClient http;
    @Inject JsonStructureInference inference;
    @Inject LlmManifestAugmenter llm;
    @Inject AuthProviderRegistry authReg;
    @Inject SecretRefs secrets;

    /**
     * Probe a path on a connector the operator named — the only way in, because it is the only one
     * a client ever used. The base URL and the credential come from the connector's own connection
     * through the run's rules ({@link EffectiveConfig}, {@link RestEndpointReader#authSpecFor}), so
     * the draft describes the source a run would actually read.
     *
     * <p>The route used to take a {@code baseUrl} and a bearer {@code token} in the request body
     * instead, and ignore the {@code connectorId} the CLI sent — which made the command answer
     * {@code 400 baseUrl is required} to every invocation, and would have required pasting a secret
     * into an HTTP body to work at all.
     */
    public String probe(Connector c, String endpointPath, String connectorName) throws Exception {
        // Refused by family BY NAME, on the hub and not only in the CLI: `lm` is not the only
        // client of this route, and every other family lacks the blocks a REST request is composed
        // from — so without this the operator reads "rest-generic requires 'base_url'" about a
        // jdbc connector and never sees the word jdbc.
        if (!RestConnectorType.KIND.equals(c.getTypeId())) {
            throw new ConfigValidationException("not a " + RestConnectorType.KIND
                + " connector (type: '" + c.getTypeId() + "') — the REST probe samples REST sources");
        }
        Map<String, Object> cfg = EffectiveConfig.of(c);
        String baseUrl = RestConnectorType.requireBaseUrl(cfg);
        return probe(baseUrl, endpointPath, RestEndpointReader.authSpecFor(cfg, new AuthSpec.Owner(c.getTenantId(), c.getId())),
            cfg.get("headers"), connectorName);
    }

    private String probe(String baseUrl, String endpointPath, AuthSpec authSpec, Object headers,
                         String connectorName) throws Exception {
        var auth = authReg.build(authSpec);
        // Before anything is sent, like the run (RestEndpointReader) and the pre-flight: the
        // factories BUILD what they have — Basic encodes `user:` when password_env resolves to
        // nothing — and refuse only here. Skipping it made the probe knock with an empty password
        // and report the source's 401 as an upstream refusal, inverting the diagnosis: "your API
        // rejected us" when the truth is "your secret is not set".
        auth.probe();
        // The COMPOSED url is what has to parse, and the endpoint path is caller input: `join`
        // concatenates whatever it is given, so a space survived to URI.create inside the HTTP
        // client and came back as an uncaught IllegalArgumentException — a 500 for something the
        // caller can fix. Same rule as the base_url check above, applied to the whole address.
        String url = Urls.join(baseUrl, endpointPath);
        Urls.whyNotAbsolute(url).ifPresent(why -> {
            throw new ConfigValidationException("the endpoint path does not compose a usable URL: " + why);
        });
        // The connector's declared headers, resolved as the run resolves them: a reference nothing
        // holds is refused here, before anything is sent.
        RestRequest req = RequestHeaders.apply(RestRequest.get(url), headers, secrets::lookup);
        RestResponse resp;
        try {
            resp = auth.send(req, http::exchange);
        } catch (InterruptedException cancelled) {
            // A cancelled request is not an answer about the source, and a plain catch would drop
            // the thread's interrupt flag. Same rule as RestEndpointReader.fetchAllPages and the
            // pre-flight: re-interrupt, then fail loudly.
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST probe interrupted", cancelled);
        }
        ResponseStatusGuard.refuseNonSuccess(resp);   // never infer a schema from an error/redirect body
        var pag = inference.inferPagination(resp.json(), resp.headers());
        var itemsPath = inference.inferItemsPath(resp.json()).orElse("$");
        JsonNode firstItem = firstItemOf(resp.json(), itemsPath);
        var uniqueKey = inference.inferUniqueKey(firstItem).orElse("$.id");
        var promotes = inference.inferPromotes(firstItem);
        return llm.augment(connectorName, endpointPath, pag, uniqueKey, promotes);
    }

    /**
     * The one record whose fields the draft describes. A path that is not walkable — {@code $}, the
     * default when {@link JsonStructureInference#inferItemsPath} recognised no collection — means
     * the document itself: its first element if it is an array, else the object as it stands.
     *
     * <p>That branch is the fix: {@code inferItemsPath} answers {@code $} only for an array, so an
     * object whose collection key is not one of the eight it knows ({@code {"partners": [...]}})
     * used to take the path walk and index {@code substring(2)} into a one-character string.
     * {@link DottedPath} carries the predicate and the split, shared with the connector validator
     * and the SOAP body parser.
     */
    private static JsonNode firstItemOf(JsonNode root, String itemsPath) {
        JsonNode node = root;
        if (DottedPath.isPlain(itemsPath)) {
            for (String segment : DottedPath.segments(itemsPath)) node = node.path(segment);
        } else if (!root.isArray()) {
            return root;                        // `$` on an object: the document IS the record
        }
        return node.isArray() && !node.isEmpty() ? node.get(0) : MAPPER.createObjectNode();
    }
}

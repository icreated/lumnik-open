package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.EffectiveConfig;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RequestHeaders;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.ResponseStatusGuard;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;
import io.lumnik.hub.connector.rest.runtime.RestConnectorType;
import io.lumnik.hub.connector.rest.runtime.RestConnection;
import io.lumnik.hub.connector.rest.runtime.RestEndpointReader;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
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
        return probe(baseUrl, endpointPath, cfg, new AuthSpec.Owner(c.getTenantId(), c.getId()), connectorName);
    }

    private String probe(String baseUrl, String endpointPath, Map<String, Object> cfg, AuthSpec.Owner owner,
                         String connectorName) throws Exception {
        // Before anything is sent, like the run (RestEndpointReader) and the pre-flight: the
        // factories BUILD what they have — Basic encodes `user:` when password_env resolves to
        // nothing — and refuse only here. Skipping it made the probe knock with an empty password
        // and report the source's 401 as an upstream refusal, inverting the diagnosis: "your API
        // rejected us" when the truth is "your secret is not set".
        RestConnection conn = RestEndpointReader.connect(cfg, owner, authReg, http, secrets);
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
        RestRequest req = RequestHeaders.apply(RestRequest.get(url), cfg.get("headers"), secrets::lookup);
        RestResponse resp;
        try {
            resp = conn.send(req, conn.client()::exchange);
        } catch (InterruptedException cancelled) {
            // A cancelled request is not an answer about the source, and a plain catch would drop
            // the thread's interrupt flag. Same rule as RestEndpointReader.fetchAllPages and the
            // pre-flight: re-interrupt, then fail loudly.
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST probe interrupted", cancelled);
        }
        ResponseStatusGuard.refuseNonSuccess(resp);   // never infer a schema from an error/redirect body
        var pag = inference.inferPagination(resp.json(), resp.headers());
        var found = inference.inferItemsPath(resp.json());
        // The list is read by the run's own reader, so what the draft describes is what a run would read.
        List<Map<String, Object>> items = found.map(path -> PaginationSupport.items(resp.json(), path)).orElse(List.of());
        JsonNode firstItem = found.isEmpty() ? resp.json()   // no collection recognised: the document IS the record
            : items.isEmpty() ? MAPPER.createObjectNode() : MAPPER.valueToTree(items.get(0));
        var uniqueKey = inference.inferUniqueKey(firstItem).orElse("$.id");
        var promotes = inference.inferPromotes(firstItem);
        return llm.augment(connectorName, endpointPath, pag, found.orElse(null), items.size(), uniqueKey, promotes);
    }
}

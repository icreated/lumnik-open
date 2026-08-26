package io.lumnik.hub.connector.rest.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RestProber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject RestHttpClient http;
    @Inject JsonStructureInference inference;
    @Inject LlmManifestAugmenter llm;
    @Inject AuthProviderRegistry authReg;

    public String probe(String baseUrl, String endpointPath, AuthSpec authSpec, String connectorName, String scope) throws Exception {
        var auth = authReg.build(authSpec);
        RestRequest req = auth.apply(RestRequest.get(Urls.join(baseUrl, endpointPath)));
        RestResponse resp = http.execute(req);
        RestHttpClient.refuseRedirect(resp);   // a redirected probe must not infer a schema from an HTML body
        var pag = inference.inferPagination(resp.json(), resp.headers());
        var itemsPath = inference.inferItemsPath(resp.json()).orElse("$");
        JsonNode firstItem;
        if (itemsPath.equals("$") && resp.json().isArray()) {
            firstItem = resp.json().size() > 0 ? resp.json().get(0) : MAPPER.createObjectNode();
        } else {
            JsonNode arr = resp.json().at("/" + itemsPath.substring(2).replace('.', '/'));
            firstItem = (arr.isArray() && arr.size() > 0) ? arr.get(0) : MAPPER.createObjectNode();
        }
        var uniqueKey = inference.inferUniqueKey(firstItem).orElse("$.id");
        var promotes = inference.inferPromotes(firstItem);
        return llm.augment(connectorName, scope, baseUrl, endpointPath, pag, itemsPath, uniqueKey, promotes, firstItem);
    }
}

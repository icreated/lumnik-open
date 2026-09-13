package io.lumnik.hub.connector.rest.discovery;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.rest.Urls;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class OpenApiImporter {

    public String importFromUrl(String url, String name, List<String> tags) {
        // What a URL holding no spec actually does, measured rather than assumed: OpenAPIV3Parser
        // does not answer null — it raises a NullPointerException from inside itself (it iterates
        // its own null message list) for an unreachable host AND for a reachable page that is not a
        // spec. Escaping here, that reached the operator as Internal Server Error plus an error id,
        // with the URL they mistyped nowhere in the answer. Naming it is this class's business:
        // what the failure means is knowledge the caller does not have.
        OpenAPI api;
        try {
            api = new OpenAPIV3Parser().read(url);
        } catch (RuntimeException unreadable) {
            throw new ConnectorException(unreadableSpec(url), unreadable);
        }
        if (api == null) throw new ConnectorException(unreadableSpec(url));   // belt: not observed
        return toManifest(api, name, tags);
    }

    /**
     * The URL is shown through {@link Urls#forDisplay}: a spec URL legally carries userinfo, and a
     * signed one carries a token in its query, either of which would otherwise land in a 502 body
     * and a hub log. Host and path survive, because a refusal that cannot say WHICH url it is about
     * helps nobody.
     */
    private static String unreadableSpec(String url) {
        return "no OpenAPI spec could be read at " + Urls.forDisplay(url)
            + " — unreachable, not a spec, or not parseable";
    }

    public String importFromYaml(String yaml, String name, List<String> tags) {
        OpenAPI api = new OpenAPIV3Parser().readContents(yaml).getOpenAPI();
        if (api == null) throw new ConnectorException("the supplied document is not a parseable OpenAPI spec");
        return toManifest(api, name, tags);
    }

    private String toManifest(OpenAPI api, String name, List<String> tags) {
        // metadata.name keeps the human label; safeName is the sanitized identifier used wherever the
        // name forms a SQL/env token (table prefix, token_env) so spaces/unicode can't break the DDL.
        String safeName = Identifiers.slug(name, "api");
        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: connectors.lumnik.io/v1\nkind: Connector\nmetadata:\n");
        // connector_type is what routes the manifest to a validator and an apply handler;
        // tenant is deliberately NOT pinned — apply refuses a manifest whose declared tenant
        // is not the authenticated one, so a hardcoded value made every draft un-appliable.
        sb.append("  name: ").append(name).append('\n').append("  connector_type: rest-generic\n");
        if (tags != null && !tags.isEmpty()) sb.append("  scopes: [").append(String.join(", ", tags)).append("]\n");
        sb.append("spec:\n  base_url: ").append(api.getServers().get(0).getUrl()).append('\n');
        sb.append("  auth: { kind: bearer, token_env: ").append(safeName.toUpperCase(Locale.ROOT)).append("_API_KEY }\n");
        sb.append("  endpoints:\n");
        // De-dup within this import so DDD-collision paths (Person_1/Person_2) and Unicode renames
        // can't yield duplicate or blank endpoint names / target tables.
        Identifiers.Unique endpointNames = new Identifiers.Unique();
        api.getPaths().forEach((path, item) -> {
            if (item.getGet() == null) return;
            String slug = endpointNames.take(Identifiers.slug(path, "endpoint"));
            boolean isCursor = item.getGet().getParameters() != null && item.getGet().getParameters().stream()
                .anyMatch(p -> "starting_after".equals(p.getName()));
            String pagination = isCursor
                ? "{ kind: cursor, cursor_param: starting_after, cursor_path: $.data[-1:].id, has_more_path: $.has_more, response_path: $.data }"
                : "{ kind: page, page_param: page, size_param: pageSize, size: 50, response_path: $.content }";
            sb.append("    - id: ").append(Identifiers.kebab(slug, "endpoint")).append('\n')
              .append("      method: GET\n      path: ").append(path).append('\n')
              .append("      pagination: ").append(pagination).append('\n')
              .append("      incremental: { mode: full }\n")
              .append("      target: { kind: hybrid, table: ext.").append(safeName).append('_').append(slug).append(", unique_key: $.id, promote: [] }\n");
        });
        return sb.toString();
    }
}

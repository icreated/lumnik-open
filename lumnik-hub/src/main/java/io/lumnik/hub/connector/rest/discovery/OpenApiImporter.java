package io.lumnik.hub.connector.rest.discovery;

import io.lumnik.hub.connector.naming.Identifiers;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class OpenApiImporter {

    public String importFromUrl(String url, String name, List<String> tags) {
        OpenAPI api = new OpenAPIV3Parser().read(url);
        return toManifest(api, name, tags);
    }

    public String importFromYaml(String yaml, String name, List<String> tags) {
        OpenAPI api = new OpenAPIV3Parser().readContents(yaml).getOpenAPI();
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
        if (tags != null && !tags.isEmpty()) sb.append("  tags: [").append(String.join(", ", tags)).append("]\n");
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

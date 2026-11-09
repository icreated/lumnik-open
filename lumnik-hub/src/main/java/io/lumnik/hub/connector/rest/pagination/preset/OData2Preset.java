package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/** SAP Gateway, OData v2: the answer under {@code d}; the endpoint's path asks for {@code $format=json&$inlinecount=allpages}. */
@ApplicationScoped
public class OData2Preset implements PaginationPreset {
    @Override public String name() { return "odata-v2"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$.d.__next", "response_path", "$.d.results",
            "total_path", "$.d.__count");   // the total is text ("130"), sent only with $inlinecount=allpages (#1827)
    }
}

package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.soap.SoapPaginationStrategy;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class PaginationFactory {

    @Inject SecretRefs secretRefs;

    public PaginationStrategy build(Map<String, Object> cfg) {
        String kind = String.valueOf(cfg.get("kind"));
        // Stringify all values so strategies receive Map<String,String> even when
        // values come from Jackson-deserialized JSONB (Integer, Boolean, etc.)
        Map<String, String> c = cfg.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        return switch (kind) {
            case "cursor"      -> new CursorPaginationStrategy(c);
            case "page"        -> new PagePaginationStrategy(c);
            case "offset"      -> new OffsetPaginationStrategy(c);
            case "link-header" -> new LinkHeaderPaginationStrategy(c);
            case "relay"       -> new RelayPaginationStrategy(c);
            case "soap"        -> new SoapPaginationStrategy(c, secretRefs::lookup);
            case "body-link"   -> new BodyLinkPaginationStrategy(c);
            case "custom"      -> {
                String scriptRef = c.get("script_ref");
                String inline    = c.get("script");
                yield inline != null
                    ? CustomPaginationStrategy.fromInlineScript(inline, c.get("response_path"))
                    : CustomPaginationStrategy.fromClasspath(scriptRef, c.get("response_path"));
            }
            default -> throw new IllegalArgumentException("Unknown pagination kind: " + kind);
        };
    }
}

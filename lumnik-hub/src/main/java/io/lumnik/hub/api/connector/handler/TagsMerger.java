package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class TagsMerger {
    private TagsMerger() {}

    public static List<String> merge(JsonNode meta, JsonNode spec) {
        List<String> out = new ArrayList<>();
        if (meta != null && meta.has("tags")) {
            meta.get("tags").forEach(t -> {
                String s = t.asText();
                if (!out.contains(s)) out.add(s);
            });
        }
        if (spec != null) {
            JsonNode targetTags = spec.path("target").path("tags");
            if (targetTags.isArray()) {
                targetTags.forEach(t -> {
                    String s = t.asText();
                    if (!out.contains(s)) out.add(s);
                });
            }
        }
        return out;
    }
}

package io.lumnik.hub.connector.rest.pagination.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects {@link PaginationPreset} beans (Open/Closed) and expands a manifest pagination block. */
@ApplicationScoped
public class PaginationPresetRegistry {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    @Inject Instance<PaginationPreset> beans;
    private final Map<String, PaginationPreset> byName = new HashMap<>();

    @PostConstruct
    void index() {
        for (PaginationPreset p : beans) {
            if (byName.putIfAbsent(p.name(), p) != null) {
                throw new IllegalStateException("Duplicate pagination preset name: " + p.name());
            }
        }
    }

    public boolean isPreset(String kind) { return byName.containsKey(kind); }

    public Set<String> requiredOverrides(String kind) {
        PaginationPreset p = byName.get(kind);
        return p == null ? Set.of() : p.requiredOverrides();
    }

    /** If {@code pagination.kind} is a preset, return the expanded real config; else return it unchanged. */
    public JsonNode expand(JsonNode pagination) {
        PaginationPreset preset = byName.get(pagination.path("kind").asText());
        if (preset == null) return pagination;

        ObjectNode out = M.valueToTree(preset.expansion());
        Set<String> vars = substitutePlaceholders(out, pagination);
        pagination.fields().forEachRemaining(e -> {
            if (!"kind".equals(e.getKey()) && !vars.contains(e.getKey())) {
                out.set(e.getKey(), e.getValue());
            }
        });
        return out;
    }

    private Set<String> substitutePlaceholders(ObjectNode out, JsonNode user) {
        Set<String> vars = new HashSet<>();
        // Collect field names first to avoid concurrent modification while putting back into out
        List<String> fields = new ArrayList<>();
        out.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode v = out.get(field);
            if (!v.isTextual()) continue;
            Matcher m = PLACEHOLDER.matcher(v.asText());
            StringBuilder sb = new StringBuilder();
            boolean found = false;
            while (m.find()) {
                found = true;
                String placeholder = m.group(1);
                vars.add(placeholder);
                m.appendReplacement(sb, Matcher.quoteReplacement(user.path(placeholder).asText()));
            }
            if (found) {
                m.appendTail(sb);
                out.put(field, sb.toString());
            }
        }
        return vars;
    }
}

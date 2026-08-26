package io.lumnik.hub.view;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the final kind:View YAML from a VALIDATED manifest — deterministically (the model
 * never writes YAML, so syntax can never fail; quoting-hostile titles are YAMLMapper's problem,
 * not ours). The ready-to-run render command rides as a first-line comment ONLY when filters
 * exist — and they were ViewFilter-validated before reaching here, so the suggested command
 * can never 400. Pure: no CDI, no I/O.
 */
public class ViewYamlAssembler {

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();

    public String assemble(ViewManifest m, List<String> filters) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "apps.lumnik.io/v1");
        root.put("kind", "View");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", m.name());
        root.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("scope", m.scope());
        spec.put("table", m.table());
        if (m.title() != null && !m.title().isBlank()) spec.put("title", m.title());
        spec.put("columns", m.columns());
        root.put("spec", spec);

        String yaml;
        try {
            yaml = MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize the manifest", e);
        }
        if (filters == null || filters.isEmpty()) return yaml;
        return commentLine(m, filters) + "\n" + yaml;
    }

    /** YAML line breaks per 1.1 (what SnakeYAML honors): LF, CR, NEL, LS, PS. */
    private static boolean isYamlLineBreak(int c) {
        return c == '\n' || c == '\r' || c == 0x0085 || c == 0x2028 || c == 0x2029;
    }

    private String commentLine(ViewManifest m, List<String> filters) {
        StringBuilder cmd = new StringBuilder("# lm view -f ").append(m.name()).append(".yaml");
        for (String f : filters) {
            if (f == null || f.isBlank())
                throw new IllegalArgumentException("filter values must not be null or blank");
            // The comment must stay ONE line: a multi-line value would inject raw YAML above
            // the manifest (e.g. a second `kind:` key). YAML 1.1 (SnakeYAML) also breaks on
            // NEL/LS/PS. Refuse loudly; the agent's retry carries it.
            if (f.chars().anyMatch(ViewYamlAssembler::isYamlLineBreak))
                throw new IllegalArgumentException("filter '"
                    + f.replaceAll("[\\n\\r\\u0085\\u2028\\u2029]", "\\\\n")
                    + "' contains a line break — filter values must be single-line");
            cmd.append(" --filter '").append(f.replace("'", "'\\''")).append('\'');
        }
        return cmd.toString();
    }
}

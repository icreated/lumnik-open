package io.lumnik.hub.view;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Assembles the final kind:View YAML from a VALIDATED manifest — deterministically (the model
 * never writes YAML, so syntax can never fail; quoting-hostile titles are YAMLMapper's problem,
 * not ours). A header of comment lines rides above it: what was asked for, what the view actually
 * narrows on, and — when there are filters — the ready-to-run render command. The filters were
 * ViewFilter-validated before reaching here, so the suggested command can never 400.
 * Pure: no CDI, no I/O.
 */
public class ViewYamlAssembler {

    /**
     * YAML line breaks per 1.1 (what SnakeYAML honors): LF, CR, NEL, LS, PS. ONE definition, read
     * by both the description that is collapsed and the filter that is refused — the character
     * class used to be written twice, twenty lines apart, which is how the two come to disagree
     * about where a comment ends. A run of them collapses to a single space.
     */
    private static final Pattern LINE_BREAK = Pattern.compile("[\\n\\r\\u0085\\u2028\\u2029]+");

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();

    public String assemble(ViewManifest m, List<String> filters, String description) {
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
        return header(m, filters, description) + yaml;
    }

    /**
     * What was asked for, and what this view actually enforces — stated even (especially) when
     * that is nothing.
     *
     * <p>A generated view can drop a condition of the description in silence: "les commandes en
     * litige de plus de 500 euros" came back narrowed on the amount alone, and the reader had to
     * diff the YAML against their own sentence to notice (#489). When EVERY condition was dropped
     * there was no comment at all, so the total gap was the one the manifest said least about.
     * The header cannot know what the model dropped — only the human can — so it states the two
     * halves side by side and lets that comparison take one glance instead of a re-read.
     *
     * <p><b>It says only what it knows.</b> The first wording ended the kept line with "nothing else
     * in that description narrows these rows" — which, on that very example, is false: <i>en
     * litige</i> does narrow them, and the model simply dropped it. Replacing a silence with a
     * confident false sentence, in the artifact the human is told to check, is worse than the
     * silence. So the line lists what IS enforced and names the comparison to make; it never claims
     * the list is complete.
     *
     * <p>The kept list and the command are built from the same validated filters, so they cannot
     * disagree.
     */
    private String header(ViewManifest m, List<String> filters, String description) {
        StringBuilder h = new StringBuilder();
        if (description != null && !description.isBlank()) {
            h.append("# asked for: ").append(oneLine(description)).append('\n');
        }
        if (filters == null || filters.isEmpty()) {
            return h.append("# kept: no condition — this view lists every row of ")
                    .append(m.table()).append('\n').toString();
        }
        // commentLine validates every filter, so it runs before the kept list is worded.
        String command = commentLine(m, filters);
        return h.append("# kept: ").append(String.join(", ", filters))
                .append(" — a condition of that description not listed here is NOT enforced\n")
                .append(command).append('\n').toString();
    }

    /**
     * The description as ONE comment line. A YAML comment ends at the first line break, so a
     * multi-line description would put its tail where the manifest goes — the same injection the
     * filter guard below refuses. Collapsed rather than refused: this is display text echoed back
     * to its own author, not a value that means something, and a human may well have pasted two
     * lines. Clipped for readability, which also bounds the comment a runaway description could
     * write.
     */
    private static String oneLine(String description) {
        return ViewSuggestion.abbreviate(LINE_BREAK.matcher(description).replaceAll(" ").strip());
    }

    private String commentLine(ViewManifest m, List<String> filters) {
        StringBuilder cmd = new StringBuilder("# lm view -f ").append(m.name()).append(".yaml");
        for (String f : filters) {
            if (f == null || f.isBlank())
                throw new IllegalArgumentException("filter values must not be null or blank");
            // The comment must stay ONE line: a multi-line value would inject raw YAML above
            // the manifest (e.g. a second `kind:` key). YAML 1.1 (SnakeYAML) also breaks on
            // NEL/LS/PS. Refuse loudly; the agent's retry carries it.
            if (LINE_BREAK.matcher(f).find())
                throw new IllegalArgumentException("filter '"
                    + LINE_BREAK.matcher(f).replaceAll("\\\\n")
                    + "' contains a line break — filter values must be single-line");
            cmd.append(" --filter '").append(f.replace("'", "'\\''")).append('\'');
        }
        return cmd.toString();
    }
}

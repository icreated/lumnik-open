package io.lumnik.hub.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The model's answer for a view generation — SELECTIONS only (the code assembles the YAML,
 * syntax can never fail). {@code abstain} is the honest exit when the scope cannot serve the
 * description. Pure: no CDI, no I/O.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ViewSuggestion(String table, String title, List<String> columns,
                             List<String> filters, boolean abstain, String reason) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fence-tolerant parse: extract the outermost {…} — small models wrap JSON in markdown fences. */
    public static ViewSuggestion parse(String raw) {
        String s = raw == null ? "" : raw;
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start)
            // The full raw output already rides separately on failures/retries — keep the message small.
            throw new IllegalArgumentException("model output is not a JSON object: " + abbreviate(s.strip()));
        try {
            return MAPPER.readValue(s.substring(start, end + 1), ViewSuggestion.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Package-visible: {@link ViewYamlAssembler} clips the echoed description to the same bound. */
    static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

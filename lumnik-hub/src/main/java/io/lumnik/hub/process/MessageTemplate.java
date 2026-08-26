package io.lumnik.hub.process;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kind:Process message template — {col} placeholders substituted from the triggering row.
 * A placeholder is exactly {@code \{[A-Za-z0-9_]+\}}; anything else ({}, {a-b}, a lone brace)
 * passes through literal — no escape syntax to learn. A null value renders as the empty string
 * (absence renders as absence, never the text "null"). Pure: no DB, no HTTP, no CDI.
 */
public final class MessageTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

    private MessageTemplate() {}

    /** The column names the template references — deduplicated, in declaration order. */
    public static List<String> placeholders(String template) {
        if (template == null) return List.of();
        Set<String> out = new LinkedHashSet<>();   // insertion-ordered dedup, O(1) per add
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    /** Substitute each placeholder with the row's value (null → empty string). */
    public static String render(String template, Map<String, Object> row) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = row.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

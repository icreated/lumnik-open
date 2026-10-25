package io.lumnik.hub.process;

import io.lumnik.hub.sql.TemporalText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code {name}} message template — kind:Process substitutes the triggering row's columns, a
 * workflow's dormancy sentence what the sweep observed (#1363).
 * A placeholder is exactly {@code \{[A-Za-z0-9_]+\}}; anything else ({}, {a-b}, a lone brace)
 * passes through literal — no escape syntax to learn. A null value renders as the empty string
 * (absence renders as absence, never the text "null"). A timestamp renders as its ISO instant in
 * UTC whatever the hub's zone (#1303); a date keeps its {@code yyyy-MM-dd}. A numeric renders as the
 * number, not at its column's scale: a source's {@code 18.7} is stored {@code 18.7000} in a
 * {@code NUMERIC(18,4)}, and the sentence says {@code 18.7} (#1357). Pure: no DB, no HTTP, no CDI.
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

    /** Substitute each placeholder with the row's value (null → empty string, timestamp → instant). */
    public static String render(String template, Map<String, Object> row) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String text = Objects.toString(text(row.get(m.group(1))), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(text));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String text(Object value) {
        return value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : TemporalText.render(value);
    }
}

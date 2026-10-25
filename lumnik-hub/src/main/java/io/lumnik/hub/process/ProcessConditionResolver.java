package io.lumnik.hub.process;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.view.ViewFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Apply-time honesty for a kind:Process `when:` list — the ViewFilter grammar validated against
 * the trigger table's SchemaCard entry: a column the table doesn't have, or an out-of-domain `=`
 * value on an allowedValues column, is REFUSED (a process must never be declared on a condition
 * that can never be true). `~` is exempt — a search, not an assertion. Pure.
 */
public final class ProcessConditionResolver {

    private ProcessConditionResolver() {}

    public static List<ViewFilter> resolve(List<String> when, SchemaCard.Table table) {
        List<ViewFilter> out = new ArrayList<>();
        for (String raw : when) {
            ViewFilter f = ViewFilter.parse(raw);
            SchemaCard.Column col = table.columns().stream()
                    .filter(c -> c.name().equals(f.column())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "when: column '" + f.column() + "' is not in " + table.name()
                            + " — fix the column name or rediscover the scope"));
            if ("=".equals(f.op()) && col.allowedValues() != null && !col.allowedValues().isEmpty()
                    && !col.allowedValues().contains(f.value()))
                throw new IllegalArgumentException("'" + f.value() + "' is not an allowed value for "
                    + f.column() + " (allowed: " + String.join(", ", col.allowedValues()) + ")");
            out.add(f);
        }
        return out;
    }

    /** Apply-time honesty for spec.emit.fields (#1365): every declared field is a trigger table column. */
    public static void validateFields(List<String> fields, SchemaCard.Table table) {
        for (String f : fields) requireColumn(f, table, "spec.emit.fields names " + f);
    }

    /** Apply-time honesty for the emit.message template: every {placeholder} must be a column
     *  of the trigger table — a typo is caught at declare time, never discovered silently. */
    public static void validateMessage(String message, SchemaCard.Table table) {
        for (String ph : MessageTemplate.placeholders(message)) requireColumn(ph, table, "message references {" + ph + "}");
    }

    private static void requireColumn(String name, SchemaCard.Table table, String what) {
        if (table.columns().stream().noneMatch(c -> c.name().equals(name)))
            throw new IllegalArgumentException(what + " — not a column of " + table.name());
    }
}

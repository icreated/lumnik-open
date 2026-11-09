package io.lumnik.hub.process;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.view.ColumnKind;
import io.lumnik.hub.view.ViewFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
            ViewFilter f = ViewFilter.parseProcessCondition(raw);
            SchemaCard.Column col = column(f.column(), table);
            if (f.operandColumn() != null) {
                requireComparable(f, col, column(f.operandColumn(), table));
            } else if ("=".equals(f.op()) && col.allowedValues() != null && !col.allowedValues().isEmpty()
                    && !col.allowedValues().contains(f.value()))
                throw new IllegalArgumentException("'" + f.value() + "' is not an allowed value for "
                    + f.column() + " (allowed: " + String.join(", ", col.allowedValues()) + ")");
            out.add(f);
        }
        return out;
    }

    private static Optional<SchemaCard.Column> find(String name, SchemaCard.Table table) {
        return table.columns().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private static SchemaCard.Column column(String name, SchemaCard.Table table) {
        return find(name, table).orElseThrow(() -> new IllegalArgumentException(
                "when: column '" + name + "' is not in " + table.name()
                    + " — fix the column name or rediscover the scope"));
    }

    /**
     * A column against a column (#1830): a condition that can never be true is refused, as for a literal.
     * Both sides must be of one kind — number, date or text — because the evaluator reads a pair of cells
     * as numbers, then as instants, and a number against a date would silently match no row. A type the
     * hub cannot order (an interval, a boolean) takes {@code =} against its own type only.
     */
    private static void requireComparable(ViewFilter f, SchemaCard.Column left, SchemaCard.Column right) {
        if (left.name().equals(right.name()))
            throw new IllegalArgumentException("when: '" + left.name() + "' is compared with itself — a column "
                + "is always equal to itself and never above it; name another column");
        if (f.ordering() && !ColumnKind.orders(left.pgType()))
            throw new IllegalArgumentException("when: cannot order '" + left.name() + "' with " + f.op() + " — a "
                + left.pgType() + " column cannot be compared that way; use = instead");
        if (!sameKind(left, right))
            throw new IllegalArgumentException("when: cannot compare '" + left.name() + "' (" + left.pgType()
                + ", " + ColumnKind.ofType(left.pgType()).wire() + ") with '" + right.name() + "' ("
                + right.pgType() + ", " + ColumnKind.ofType(right.pgType()).wire()
                + ") — both columns must be numbers, dates or texts");
    }

    private static boolean sameKind(SchemaCard.Column left, SchemaCard.Column right) {
        ColumnKind a = ColumnKind.ofType(left.pgType()), b = ColumnKind.ofType(right.pgType());
        if (a.number() || b.number()) return a.number() && b.number();
        if (a.temporal() || b.temporal()) return a.temporal() && b.temporal();
        if (a == ColumnKind.OPAQUE || b == ColumnKind.OPAQUE) return a == b && left.pgType().equalsIgnoreCase(right.pgType());
        return true;   // text and choice: exact text, or text that holds a number or a date
    }

    /** Apply-time honesty for spec.emit.fields (#1365): every declared field is a trigger table column. */
    public static void validateFields(List<String> fields, SchemaCard.Table table) {
        for (String f : fields) requireColumn(f, table, "spec.emit.fields names " + f);
    }

    /** The decision's key column, when declared, is a column of the trigger table (#1823). */
    public static void validateKey(String key, SchemaCard.Table table) {
        if (key != null) requireColumn(key, table, "spec.emit.key names " + key);
    }

    /** Apply-time honesty for the emit.message template: every {placeholder} must be a column
     *  of the trigger table — a typo is caught at declare time, never discovered silently. */
    public static void validateMessage(String message, SchemaCard.Table table) {
        for (String ph : MessageTemplate.placeholders(message)) requireColumn(ph, table, "message references {" + ph + "}");
    }

    private static void requireColumn(String name, SchemaCard.Table table, String what) {
        if (find(name, table).isEmpty())
            throw new IllegalArgumentException(what + " — not a column of " + table.name());
    }
}

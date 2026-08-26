package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One runtime filter on a kind:View list or a kind:Process when-condition:
 * {@code col=value} (exact), {@code col~text} (substring, case-insensitive), or an ordering
 * comparison {@code col>value col<value col>=value col<=value} whose value MUST be a number
 * or an ISO-8601 date/timestamp — refused loudly otherwise. Honesty line: a malformed filter,
 * a column the view does not show, or an out-of-domain {@code =} value on an
 * {@code allowedValues} column is REFUSED — never a lying zero-row table. {@code ~} and the
 * ordering operators are exempt from the domain check: a search / a threshold is not a
 * membership assertion. The comparable shapes below are shared verbatim by the SQL world
 * (PG {@code ~} guard) and the in-memory evaluator, so the two can never disagree on a row.
 * Pure: no DB, no HTTP.
 */
public record ViewFilter(String column, String op, String value) {

    /** Two-char operators FIRST — a naive scan would split "montant>=10" on the '='. */
    private static final String[] OPS = {">=", "<=", ">", "<", "=", "~"};
    private static final Set<String> ORDERING = Set.of(">", "<", ">=", "<=");

    /** Plain decimal — scientific notation is "dirty" in BOTH worlds. PG-~ and Java-Pattern compatible. */
    public static final String NUMERIC_SHAPE = "^\\s*-?[0-9]+(\\.[0-9]+)?\\s*$";
    /** ISO date, optionally followed by a T- or space-separated well-formed ISO time. */
    public static final String TEMPORAL_SHAPE =
        "^\\s*[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"
        + "([ T]([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9](\\.[0-9]+)?)?([+-]([01][0-9]|2[0-3])(:[0-5][0-9])?|Z)?)?"
        + "\\s*$";

    public enum OperandType { NUMERIC, TEMPORAL }

    public static ViewFilter parse(String raw) {
        // Scan left-to-right, two-char operators first, and build from the first match
        // in the same scope — so the operator is never a nullable sentinel.
        for (int i = 0; i < raw.length(); i++) {
            for (String op : OPS) {
                if (!raw.startsWith(op, i)) continue;
                String column = raw.substring(0, i).strip();
                String value = raw.substring(i + op.length()).strip();
                if (column.isBlank() || value.isBlank())
                    throw new IllegalArgumentException(
                        "filter '" + raw + "' needs a column and a value — col=value, col~text, or col>value");
                ViewFilter f = new ViewFilter(column, op, value);
                if (f.ordering() && f.operandType() == null)
                    throw new IllegalArgumentException(
                        "'" + value + "' is not a number or ISO date — " + op + " needs an ordered value");
                return f;
            }
        }
        throw new IllegalArgumentException(
            "filter '" + raw + "' has no operator — use col=value (exact), col~text (contains), "
            + "or col>value col<value col>=value col<=value (ordered)");
    }

    /** True for the four comparison operators. */
    public boolean ordering() {
        return ORDERING.contains(op);
    }

    /** The ordered operand's type — null when the value is neither (or the op is =/~). */
    public OperandType operandType() {
        if (!ordering()) return null;
        if (value.matches(NUMERIC_SHAPE)) {
            try {
                new BigDecimal(value.strip());
                return OperandType.NUMERIC;
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        if (value.matches(TEMPORAL_SHAPE) && parseTemporal(value) != null) return OperandType.TEMPORAL;
        return null;
    }

    /** ISO date / datetime (T or space) / offset datetime → Instant; date-only = midnight UTC. */
    public static Instant parseTemporal(String s) {
        // Normalize the two PG-ism gaps vs strict ISO: a space date/time separator, and a whole-hour
        // zone offset rendered as "+00" (ISO wants "+00:00"). Keeps parseTemporal accepting every form
        // TEMPORAL_SHAPE accepts, so the SQL guard and the in-memory evaluator never disagree on a row.
        // The offset pad only fires once a time component is present (a 'T' separator) — otherwise a
        // plain date's "-01" day-of-month would be mistaken for a short "-01" zone offset.
        String t = s.strip().replace(' ', 'T');
        if (t.indexOf('T') >= 0) t = t.replaceAll("([+-][0-9]{2})$", "$1:00");
        try { return OffsetDateTime.parse(t).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(t).atOffset(ZoneOffset.UTC).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (DateTimeParseException ignored) { }
        return null;
    }

    /** Parse + validate all filters against the resolved view and its card table. */
    public static List<ViewFilter> resolveAll(List<String> raw, ResolvedView rv, SchemaCard.Table table) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<ViewFilter> out = new ArrayList<>();
        for (String r : raw) {
            ViewFilter f = parse(r);
            boolean shown = rv.columns().stream().anyMatch(c -> c.name().equals(f.column()));
            if (!shown)
                throw new IllegalArgumentException("cannot filter on '" + f.column()
                    + "' — the view does not show it; declare it in spec.columns to filter on it");
            if ("=".equals(f.op())) {
                SchemaCard.Column card = table.columns().stream()
                        .filter(c -> c.name().equals(f.column())).findFirst().orElse(null);
                if (card != null && card.allowedValues() != null && !card.allowedValues().isEmpty()
                        && !card.allowedValues().contains(f.value()))
                    throw new IllegalArgumentException("'" + f.value() + "' is not an allowed value for "
                        + f.column() + " (allowed: " + String.join(", ", card.allowedValues()) + ")");
            }
            out.add(f);
        }
        return out;
    }
}

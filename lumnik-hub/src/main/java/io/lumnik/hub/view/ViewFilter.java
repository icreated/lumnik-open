package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.sql.TemporalText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One runtime filter on a kind:View list or a kind:Process when-condition:
 * {@code col=value} (exact), {@code col~text} (substring, case-insensitive), or an ordering
 * comparison {@code col>value col<value col>=value col<=value} whose value MUST be a number
 * or an ISO-8601 date/timestamp — refused loudly otherwise. A value is a BARE literal:
 * {@code city=Lyon}, never {@code city='Lyon'}. Honesty line: a malformed filter, a quoted
 * value, a column the view does not show, or an out-of-domain {@code =} value on an
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

    /** The operator at {@code i}, two-char forms first ({@code >=} before {@code >}), or null. */
    private static String opAt(String raw, int i) {
        for (String op : OPS) if (raw.startsWith(op, i)) return op;
        return null;
    }

    public static ViewFilter parse(String raw) {
        // Scan left-to-right and build from the first match in the same scope — so the operator
        // is never a nullable sentinel.
        for (int i = 0; i < raw.length(); i++) {
            String op = opAt(raw, i);
            if (op != null) {
                String column = raw.substring(0, i).strip();
                String value = raw.substring(i + op.length()).strip();
                if (column.isBlank() || value.isBlank())
                    throw new IllegalArgumentException(
                        "filter '" + raw + "' needs a column and a value — col=value, col~text, or col>value");
                refuseQuotedValue(column, op, value);
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

    /**
     * The same filter with SQL quotes shed from its value — {@code segment='GOLD'} becomes
     * {@code segment=GOLD}. A value in this grammar is a bare literal, so a quoted one is refused
     * by the domain check and, on a column with no domain, silently matches nothing: the lying
     * zero-row table this class exists to prevent. Only a MODEL-generated filter is passed through
     * here; a hand-written manifest means what it writes.
     *
     * <p>Bounded on purpose: SQL escaping ({@code 'O''Brien'}) is a shape this grammar does not
     * model, so a value carrying its own quote is left whole for {@link #parse} to refuse, never
     * half-decoded into a value nobody wrote.
     */
    public static String withUnquotedValue(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            String op = opAt(raw, i);
            if (op == null) continue;
            String tail = raw.substring(i + op.length());
            String value = tail.strip();
            String bare = shedQuotes(value);
            if (bare.equals(value)) return raw;
            // Splice into the tail's own spacing rather than rebuilding: the generated filter is
            // shown to a human on the manifest's first line, so `city ~ 'Lyon'` comes back as
            // `city ~ Lyon`, keeping what the model wrote apart from the quotes.
            int at = tail.indexOf(value);
            return raw.substring(0, i + op.length())
                + tail.substring(0, at) + bare + tail.substring(at + value.length());
        }
        return raw;   // no operator: parse() owns that refusal, not this normalisation
    }

    private static String shedQuotes(String value) {
        String inner = quotedInner(value);
        if (inner == null) return value;
        // 'O''Brien' keeps its quotes here and is refused by parse — see quotedInner.
        return inner.indexOf(value.charAt(0)) >= 0 ? value : inner;
    }

    /**
     * The text between matching outer quotes, or null when the value carries none. One place
     * recognises a quoted value, because the two readers must never disagree about what one is:
     * {@link #shedQuotes} strips them off a MODEL's filter, {@link #refuseQuotedValue} refuses
     * them in a HUMAN's.
     *
     * <p><b>Balanced only, on purpose.</b> {@code city='Lyon} and {@code city=Lyon'} are still
     * accepted, and still match nothing — the same silence, half-closed. They stay accepted because
     * a lone apostrophe IS data in this domain ({@code l'ecole}, {@code O'Brien}), and a rule that
     * refused every value carrying one would refuse more real filters than slips. A BALANCED pair is
     * the shape no value legitimately has, which is why it is the one this refuses.
     */
    private static String quotedInner(String value) {
        if (value.length() < 2) return null;
        char quote = value.charAt(0);
        if ((quote != '\'' && quote != '"') || value.charAt(value.length() - 1) != quote) return null;
        return value.substring(1, value.length() - 1);
    }

    /**
     * A value in this grammar is a BARE literal: {@code city=Lyon}, not {@code city='Lyon'}.
     * Quotes are ordinary characters here, so a quoted value used to be compared quotes and all —
     * accepted at apply, matching nothing for ever, saying nothing. That silence is the one thing
     * this class exists to prevent, so the quotes are refused at the door instead (#503).
     *
     * <p>Refused, not rewritten: a hand-written manifest means what it writes, and silently
     * changing a human's text is how a filter comes to mean something nobody declared. The
     * model's own filter never reaches here quoted — {@link #withUnquotedValue} sheds them first,
     * because echoing SQL syntax is the model's mistake to absorb, not the operator's to read.
     *
     * <p>The bare form is only suggested when it is unambiguous: {@code 'O''Brien'} would become
     * {@code O''Brien}, a value nobody wrote, so that one gets the rule and no rewrite.
     */
    private static void refuseQuotedValue(String column, String op, String value) {
        String inner = quotedInner(value);
        if (inner == null) return;
        String bare = inner.isEmpty() || inner.indexOf(value.charAt(0)) >= 0
            ? "" : " — write " + column + op + inner;
        throw new IllegalArgumentException("filter value " + value + " is quoted; a value here is a"
            + " bare literal, so the quotes would be compared to " + column + " too" + bare);
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
        if (value.matches(TEMPORAL_SHAPE) && TemporalText.parse(value) != null) return OperandType.TEMPORAL;
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

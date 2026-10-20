package io.lumnik.hub.view;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a user can do with a View column, decided once by the hub from the card's Postgres type
 * (information_schema's {@code data_type} vocabulary) and the declared domain. Every client reads
 * this instead of re-deriving it from a type name.
 *
 * <p>The announced kind is tied to what {@link ViewDataReader} can actually evaluate: it compares by
 * casting the column to text and shape-checking the text ({@link ViewFilter#NUMERIC_SHAPE},
 * {@link ViewFilter#TEMPORAL_SHAPE}), so a type whose text form fails those shapes ({@code money}'s
 * {@code $1,234.50}, {@code time}, {@code interval}) can never match an ordering filter. Such a type
 * is {@link #OPAQUE} here — never a number or a date, searched as text — and the filter gate refuses
 * ordering on it ({@link #orders}) instead of serving a lying zero-row table. What a kind announces and
 * what the gate enforces come from the same constant. Pure: no DB, no HTTP.
 */
public enum ColumnKind {
    CHOICE("choice", List.of("="), true),
    /** A textual column: searched with {@code ~} and {@code =}; an ordering is still accepted, because a CSV-landed number is text. */
    TEXT("text", List.of("~", "="), true),
    NUMBER("number", ordered(), true),
    /** A floating-point column: announced as a number, but compared natively (its text form can be scientific). */
    FLOAT("number", ordered(), true),
    DATE("date", ordered(), true),
    DATETIME("datetime", ordered(), true),
    /** A type the engine cannot order (money, time, interval, boolean, uuid…). Reaches clients as {@code text}. */
    OPAQUE("text", List.of("~", "="), false);

    private static final Set<String> TEXTUAL = Set.of("text", "character varying", "varchar", "character", "char", "bpchar");
    private static final Set<String> FLOATS = Set.of("real", "double precision", "float4", "float8");
    private static final Set<String> NUMERIC = Set.of("smallint", "integer", "bigint", "numeric", "decimal",
        "int2", "int4", "int8");

    private final String wire;
    private final List<String> operators;
    private final boolean orders;

    ColumnKind(String wire, List<String> operators, boolean orders) {
        this.wire = wire;
        this.operators = operators;
        this.orders = orders;
    }

    /** The operators a client may offer for this kind, in the order to show them. */
    public List<String> operators() {
        return operators;
    }

    /** The name clients read: {@link #FLOAT} is announced as {@code number}, {@link #OPAQUE} as {@code text}. */
    public String wire() {
        return wire;
    }

    /** The operators of every ordered kind (an enum constant cannot read the enum's own static fields, a method it can). */
    private static List<String> ordered() {
        return List.of("=", ">=", "<=", ">", "<");
    }

    /** A date or a timestamp column: compared as an instant against a date (#1310, #1325). */
    public boolean temporal() {
        return this == DATE || this == DATETIME;
    }

    /** Whether an ordering filter ({@code > < >= <=}) can match on this kind. */
    public boolean orders() {
        return orders;
    }

    /** The kind of a column: a declared domain wins, then the type; an unknown (null) type is text, as the SQL treats it. */
    public static ColumnKind of(String pgType, List<String> allowedValues) {
        if (allowedValues != null && !allowedValues.isEmpty()) return CHOICE;
        if (pgType == null) return TEXT;
        String t = base(pgType);
        if (NUMERIC.contains(t)) return NUMBER;
        if (FLOATS.contains(t)) return FLOAT;
        if (t.equals("date")) return DATE;
        if (t.startsWith("timestamp")) return DATETIME;
        return TEXTUAL.contains(t) ? TEXT : OPAQUE;
    }

    /** The kind of a type alone, its declared domain ignored — what the SQL compares. */
    public static ColumnKind ofType(String pgType) {
        return of(pgType, null);
    }

    /**
     * The SQL fragment ordering a column of this kind against a validated operand. Ordering rides a
     * CASE guard: PG does not guarantee WHERE predicate order, so a bare AND shape-check could cast a
     * dirty row first and 500 — CASE fixes the evaluation order, and a row whose text is not of the
     * operand's shape (the incomparable) falls to ELSE false. A {@link #FLOAT} is the exception: it is
     * already numeric, and its text form can be scientific ({@code 1e-05}, {@code 1e+20}), which the
     * shape would reject, so it compares natively. The operator and shape come from closed internal
     * sets; the value stays bound. Throws for a kind that cannot order — the filter gate refuses
     * those first, so reaching here is a bug, never user input.
     */
    public String orderingSql(String quotedColumn, String op, ViewFilter.OperandType operand) {
        if (!orders) throw new IllegalStateException(this + " cannot be ordered: " + quotedColumn + " " + op);
        boolean numeric = operand == ViewFilter.OperandType.NUMERIC;
        if (numeric && this == FLOAT) return quotedColumn + " " + op + " ?::float8";
        String shape = numeric ? ViewFilter.NUMERIC_SHAPE : ViewFilter.TEMPORAL_SHAPE;
        String cast = numeric ? "numeric" : "timestamptz";
        return "CASE WHEN " + quotedColumn + "::text ~ '" + shape + "' "
             + "THEN (" + quotedColumn + "::text)::" + cast + " " + op + " ?::" + cast + " "
             + "ELSE false END";
    }

    /**
     * Whether an ordering against an operand of this family can match on this kind. The SQL shape guard
     * is chosen by the OPERAND, so a number against a date column (or a date against a number column)
     * drops every row: a lying zero-row table. A number column is ordered by a number, a date column by a
     * date; text may hold either (a CSV-landed number or date).
     */
    public boolean accepts(ViewFilter.OperandType operand) {
        if (!orders) return false;
        return switch (this) {
            case NUMBER, FLOAT -> operand == ViewFilter.OperandType.NUMERIC;
            case DATE, DATETIME -> operand == ViewFilter.OperandType.TEMPORAL;
            default -> true;
        };
    }

    /** Whether an ordering filter can match on a column of this type — the filter gate's question. */
    public static boolean orders(String pgType) {
        return ofType(pgType).orders;
    }

    /** Whether the type is already textual, so comparing it needs no cast ({@code information_schema} spells the varchar family out in words). */
    public static boolean isTextual(String pgType) {
        return pgType != null && TEXTUAL.contains(base(pgType));
    }

    /** Lower case, parameters ({@code numeric(12,2)}) dropped. */
    private static String base(String pgType) {
        String t = pgType.toLowerCase(Locale.ROOT).strip();
        int paren = t.indexOf('(');
        return paren < 0 ? t : t.substring(0, paren).strip();
    }
}

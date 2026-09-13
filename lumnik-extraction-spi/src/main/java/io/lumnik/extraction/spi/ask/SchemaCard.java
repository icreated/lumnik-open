package io.lumnik.extraction.spi.ask;

import java.util.List;

/**
 * Persisted, per-scope schema enriched with business labels: the level-1 registry card
 * (table/column names + inferred types), plus optional level-2 grounding (description,
 * example value, allowed-values domain) that reduces hallucination in generated SQL.
 */
public record SchemaCard(String scope, List<Table> tables) {

    /**
     * A column the SQL-generation model may use. Beyond the structural {@code label}, optional
     * grounding reduces hallucination: a {@code description} (business meaning), an {@code example}
     * value, and the {@code allowedValues} domain (the DomainGuard refuses a filter on a value not
     * in this set). The grounding fields are nullable for backward compatibility (old cards / connector
     * tables carry none); the 3-arg constructor preserves all existing call sites.
     */
    public record Column(String name, String pgType, String label,
                         String description, String example, List<String> allowedValues) {
        public Column {
            requireName(name, "column name");
        }

        public Column(String name, String pgType, String label) {
            this(name, pgType, label, null, null, null);
        }
    }

    public record Table(String name, String label, List<Column> columns, String description) {
        public Table {
            requireName(name, "table name");
        }

        public Table(String name, String label, List<Column> columns) {
            this(name, label, columns, null);
        }
    }

    /**
     * The one field of a card that every reader dereferences: {@code LeakGuard} and
     * {@code UndocumentedColumnGuard} fold it to lower case, five apply-time resolvers compare it, and
     * the renderer quotes it. A null or blank name is not a degraded card but an unusable one, and it
     * used to TRAVEL — the render appended the literal {@code null} and the crash landed in a guard,
     * one model call later, as an anonymous 500. Refusing it here is the shape that prevents the whole
     * class, instead of a null check per reader.
     *
     * <p>No builder can produce one ({@code connector.dynamic_table.table_name} is NOT NULL, and JSON
     * object keys cannot be null), so what this actually closes is the unvalidated path: a corrupt or
     * hand-edited {@code connector.schema_card} row, which Jackson hands to this very constructor.
     */
    private static void requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                what + " must not be null or blank — a schema card whose name cannot name anything is "
                    + "unusable, and every reader of the card dereferences it");
        }
    }

    public List<String> tableNames() {
        return tables.stream().map(Table::name).toList();
    }

    /**
     * Render for the SQL-generation prompt: each column shows its meaning, examples, and allowed
     * values — the card as the model sees it.
     *
     * <p>Rendering only, one method per nesting level so each reads at its own scale. (S3776: six
     * repetitions of "present and not blank" carried most of the count.)
     */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Table t : tables) appendTable(sb, t);
        return sb.toString();
    }

    private static void appendTable(StringBuilder sb, Table t) {
        sb.append("TABLE ").append(quotedTable(t.name()));
        if (present(t.label())) sb.append("  -- ").append(t.label());
        if (present(t.description())) sb.append(" (").append(t.description()).append(')');
        sb.append('\n');
        for (Column c : t.columns()) appendColumn(sb, c);
        sb.append('\n');
    }

    private static void appendColumn(StringBuilder sb, Column c) {
        sb.append("  ").append(quoted(c.name())).append(' ').append(c.pgType());
        String meaning = present(c.description()) ? c.description() : c.label();
        if (present(meaning)) sb.append("  -- ").append(meaning);
        String grounding = grounding(c);
        if (!grounding.isEmpty()) sb.append(" [").append(grounding).append(']');
        sb.append('\n');
    }

    /** The bracketed hints: an example, the declared value domain, or both. */
    private static String grounding(Column c) {
        StringBuilder extra = new StringBuilder();
        if (present(c.example())) extra.append("e.g. ").append(c.example());
        if (c.allowedValues() != null && !c.allowedValues().isEmpty()) {
            if (extra.length() > 0) extra.append("; ");
            extra.append("allowed: ").append(String.join(", ", c.allowedValues()));
        }
        return extra.toString();
    }

    /**
     * A table reference as the model must copy it: {@code schema.table} with both parts quoted, a bare
     * name quoted whole — splitting on the FIRST dot, the boundary {@code SqlIdentifiers.quoteTable}
     * already draws for real SQL.
     *
     * <p>The manifest door was supposed to make this unnecessary, and does not: its predicate is
     * {@code [A-Za-z_][A-Za-z0-9_]*}, which accepts {@code Orders}, and nothing lowercases a manifest
     * name — so the writer creates {@code connector.t_Orders} and the registry reads it straight back
     * into a card. Measured under the empty {@code search_path} the ask path uses: bare, the model's
     * copy answers {@code relation "connector.t_orders" does not exist}; quoted, it returns rows.
     */
    private static String quotedTable(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? quoted(name)
            : quoted(name.substring(0, dot)) + "." + quoted(name.substring(dot + 1));
    }

    /**
     * The column name as the model must copy it: double-quoted, embedded quotes doubled.
     *
     * <p>A column name is whatever the source called its field: nothing validates it — unlike the table,
     * which at least passes a door predicate (an insufficient one, see {@link #quotedTable}) — and the
     * kafka reader has no mapping block to rename one. Rendered bare, three real names
     * from the kafka bench were unaskable: {@code c_bpartner.id} (the dot is the qualifier separator,
     * so the model's copy named a table that does not exist), {@code Partner Name} (the rendered line
     * {@code Partner Name text} cannot even be parsed by its reader, and the model INVENTED
     * {@code Partner_Name} against the prompt's own rule), and {@code MONTANT_€} (bare, it case-folds).
     * Quoted, all three answer.
     *
     * <p>Unconditional, because a predicate here would be a second thing to keep true: on plain names
     * the quoted card produced byte-identical SQL (measured, qwen2.5-coder:7b at temperature 0) since the
     * model drops quotes it does not need. So there is no allowlist, no keyword table — a column named
     * {@code order} is carried by the same line.
     *
     * <p>Render-time only. The stored {@link Column#name()} is what {@code UndocumentedColumnGuard}
     * compares a filtered column against and what {@code LeakGuard} folds into its declared set;
     * quoting at build time would poison both, and every persisted card with them. (The executable twin
     * for real SQL is {@code SqlIdentifiers.quoteIdent} in the hub — a module the SPI cannot see. This
     * is prompt text, not a statement, so it is not that class's injection surface.)
     */
    private static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}

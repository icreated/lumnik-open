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
        public Column(String name, String pgType, String label) {
            this(name, pgType, label, null, null, null);
        }
    }

    public record Table(String name, String label, List<Column> columns, String description) {
        public Table(String name, String label, List<Column> columns) {
            this(name, label, columns, null);
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
        sb.append("TABLE ").append(t.name());
        if (present(t.label())) sb.append("  -- ").append(t.label());
        if (present(t.description())) sb.append(" (").append(t.description()).append(')');
        sb.append('\n');
        for (Column c : t.columns()) appendColumn(sb, c);
        sb.append('\n');
    }

    private static void appendColumn(StringBuilder sb, Column c) {
        sb.append("  ").append(c.name()).append(' ').append(c.pgType());
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

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}

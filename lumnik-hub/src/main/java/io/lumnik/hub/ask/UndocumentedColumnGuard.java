package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Refuses a generated query that filters a column NOT declared in the {@link SchemaCard} — the
 * column-level twin of {@link DomainGuard}. Catches an invented column (today a raw SQL error) and a
 * real-but-undeclared internal column ({@code tenant_id}, {@code _row_hash}) the chat was never meant to
 * reason over — when it is filtered against a STRING literal.
 *
 * <p><b>High-confidence + fail-open.</b> String literals become single tokens, so a column name inside a
 * literal can't trigger a refusal. Only a clean, bare, unqualified identifier on the left of
 * {@code = / <> / !=} (vs a string literal) or {@code IN (<clean literal list>)} is inspected;
 * a qualified ({@code t.col}), quoted ({@code "col"}), or function-wrapped ({@code lower(col)}) LHS, and
 * a small set of non-column keywords, are never inspected — a miss, never an over-block. A bare column is
 * refused only when it is in NO declared table (the union across the whole card).
 */
@ApplicationScoped
public class UndocumentedColumnGuard {

    /** A filtered column that is not declared anywhere in the card. */
    public record Violation(String column, List<String> declaredColumns) {
        private static final int MAX_SHOWN = 12; // cap the "known" list so a wide card can't bloat the message/logs

        public String message() {
            String known = declaredColumns.size() <= MAX_SHOWN
                ? String.join(", ", declaredColumns)
                : String.join(", ", declaredColumns.subList(0, MAX_SHOWN))
                    + ", … (+" + (declaredColumns.size() - MAX_SHOWN) + " more)";
            return "I won't run this: '" + column + "' is not a declared column of this scope (known: "
                + known + "). If it should exist, add it to the entity manifest.";
        }
    }

    // SQL niladic value keywords that can appear bare on the left of an operator but are NOT columns
    // (incl. bare `user`, a synonym of current_user) — fail-open rather than refuse them as undeclared.
    private static final Set<String> NOT_COLUMNS = Set.of(
        "true", "false", "null", "user", "current_user", "session_user", "current_role",
        "current_catalog", "current_schema", "current_date", "current_time", "current_timestamp",
        "localtime", "localtimestamp");

    public Optional<Violation> check(String sql, SchemaCard card) {
        if (sql == null || card == null) return Optional.empty();

        Set<String> declared = new LinkedHashSet<>();
        for (SchemaCard.Table t : card.tables()) {
            for (SchemaCard.Column c : t.columns()) declared.add(c.name().toLowerCase(Locale.ROOT));
        }
        if (declared.isEmpty()) return Optional.empty();

        for (SqlFilters.Filter f : SqlFilters.columnLiteralFilters(SqlLexer.tokenize(sql))) {
            String col = f.column().toLowerCase(Locale.ROOT);
            if (NOT_COLUMNS.contains(col) || declared.contains(col)) continue;  // keyword / declared -> skip
            return Optional.of(new Violation(f.column(), List.copyOf(declared)));
        }
        return Optional.empty();
    }
}

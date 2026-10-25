package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
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
 * literal can't trigger a refusal. Only a clean, unqualified identifier on the left of
 * {@code = / <> / !=} (vs a string literal), {@code [NOT] LIKE / ILIKE} (a pattern) or {@code IN (<clean literal list>)} is inspected —
 * QUOTED OR NOT, since the card renders every column quoted and a model copying what it sees would
 * otherwise walk straight past this guard; a qualified ({@code t.col}) or function-wrapped
 * ({@code lower(col)}) LHS, and a small set of non-column keywords, are never inspected — a miss,
 * never an over-block. A bare column is
 * refused only when it is in NO declared table (the union across the whole card).
 */
@ApplicationScoped
public class UndocumentedColumnGuard {

    /** A filtered column that is not declared anywhere in the card. */
    public record Violation(String column, List<String> declaredColumns) {
        public String message() {
            return message(null);
        }

        public String message(String lang) {
            return AskSentences.undocumentedColumn(lang, column, declaredColumns);
        }
    }

    public Optional<Violation> check(String sql, SchemaCard card) {
        if (sql == null || card == null) return Optional.empty();

        Set<String> declared = new LinkedHashSet<>();
        for (SchemaCard.Table t : card.tables()) {
            for (SchemaCard.Column c : t.columns()) declared.add(c.name().toLowerCase(Locale.ROOT));
        }
        if (declared.isEmpty()) return Optional.empty();

        var tokens = SqlLexer.tokenize(sql);
        var filters = new ArrayList<>(SqlFilters.columnLiteralFilters(tokens));
        filters.addAll(SqlFilters.columnPatternFilters(tokens)); // a LIKE / ILIKE search names a column too (#1047)
        for (SqlFilters.Filter f : filters) {
            String col = f.column().toLowerCase(Locale.ROOT);
            // A niladic keyword (current_date, bare user) is fail-open rather than refused as undeclared. Only
            // when the query spelled it BARE: the card hands the model every name quoted, and `"user" = '…'`
            // filters a column named user — treating that as the keyword would walk past a real filter.
            if (SqlFilters.isValueKeyword(f.column(), f.quoted()) || declared.contains(col)) continue;
            return Optional.of(new Violation(f.column(), List.copyOf(declared)));
        }
        return Optional.empty();
    }
}

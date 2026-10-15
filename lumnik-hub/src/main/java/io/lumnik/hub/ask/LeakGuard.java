package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Refuses a generated query that reads a SCHEMA-QUALIFIED table not declared in the scope's
 * {@link SchemaCard} — the table-level twin of {@link DomainGuard} (value) and
 * {@link UndocumentedColumnGuard} (column). This is the scope/métier boundary on the generated SQL:
 * {@code lumnik_readonly} can read every connector table and RLS only filters by tenant, so without this
 * a chat scoped to one métier could read another's table within the same tenant.
 *
 * <p><b>High-confidence + fail-open.</b> Inspects every {@code schema.table} reference whose schema is one
 * the card declares — position-independent (FROM, JOIN, comma-join, ONLY/LATERAL, subquery, SELECT-list),
 * so a qualified cross-scope read can't escape via a comma-join or a keyword. The schema filter is the
 * discriminator that avoids over-blocking: {@code alias.column} (e.g. {@code t.code}) has an alias, not a
 * declared schema, as its first part, so it is not inspected — the one exception being a query that names a
 * table alias literally like a declared schema ({@code FROM v t connector} then {@code connector.code}),
 * a pathological shape the SQL-gen prompt never produces. A bare name (CTE/alias — closed at the execution
 * layer by an empty {@code search_path}), a function call {@code schema.fn(...)}, a cast
 * {@code ::schema.type}, or a {@code schema.table} inside a literal/comment is never matched (a miss, never
 * an over-block). Quoted ({@code "schema"."table"}) and spaced ({@code schema . table}) forms ARE matched
 * (they resolve to the same table — the boundary can't be quoted around). Tokenized via
 * {@link SqlLexer}, which skips comments and captures string literals natively, so a
 * {@code schema.table} inside a literal or comment is never matched.
 *
 * <p><b>Database-qualified (three-part) names.</b> {@code db.schema.table} is also matched, by evaluating
 * the trailing {@code schema.table} pair of the dotted chain, not just the leading pair — Postgres accepts
 * this form when {@code db} is the current catalogue, and an empty {@code search_path} does not block it.
 * The same declared-schema filter that discriminates {@code alias.column} discriminates this case too: the
 * leading {@code db.schema} pair is examined and skipped ({@code db} is never a declared schema), and the
 * trailing {@code schema.table} pair is examined and, if undeclared, refused. This does not reopen the
 * {@code schema.table.column} case (e.g. {@code connector.v_customer.code}): its trailing pair is
 * {@code table.column}, and {@code table} is not itself a declared schema, so it is filtered exactly like
 * {@code alias.column}.
 */
@ApplicationScoped
public class LeakGuard {

    /** A qualified table read by the query that is not declared in the scope's card. */
    public record Violation(String table, List<String> declaredTables) {
        public String message() {
            return message(null);
        }

        public String message(String lang) {
            return AskSentences.leak(lang, table, declaredTables);
        }
    }

    /** All hub tables/views live here ({@code EntityViewCompiler.SCHEMA}); used to qualify a legacy card. */
    private static final String HUB_SCHEMA = "connector";

    /**
     * Build the declared surface, then look for the first schema-qualified read outside it.
     * (S3776: a card fold and a token scan living in one method.)
     */
    public Optional<Violation> check(String sql, SchemaCard card) {
        if (sql == null || card == null) return Optional.empty();
        Set<String> declared = declaredTables(card);
        if (declared.isEmpty()) return Optional.empty();
        return firstUndeclaredRead(SqlLexer.tokenize(sql), declared, schemasOf(declared));
    }

    /**
     * Every table the card declares, schema-qualified (a legacy card without a schema gets the hub's).
     *
     * <p>S2692: {@code indexOf('.') > 0}, not {@code >= 0}, is deliberate. A name whose first char is
     * a dot has no schema part to keep, and {@code >= 0} would send it to {@link #schemasOf} where
     * {@code substring(0, 0)} yields an EMPTY schema — a declaration matching nothing, silently.
     * With {@code > 0} such a name becomes {@code connector..x}, which matches no token the lexer
     * produces, so the guard refuses rather than admits. Both are unreachable from a generated card
     * (builders emit {@code schema.name}); the difference only decides which way malformed input
     * fails, and this one fails closed.
     */
    @SuppressWarnings("java:S2692")
    private static Set<String> declaredTables(SchemaCard card) {
        Set<String> declared = new LinkedHashSet<>();
        for (SchemaCard.Table t : card.tables()) {
            String name = t.name().toLowerCase(Locale.ROOT);
            declared.add(name.indexOf('.') > 0 ? name : HUB_SCHEMA + "." + name);
        }
        return declared;
    }

    private static Set<String> schemasOf(Set<String> declared) {
        Set<String> schemas = new HashSet<>();
        for (String fqn : declared) schemas.add(fqn.substring(0, fqn.indexOf('.')));
        return schemas;
    }

    private static Optional<Violation> firstUndeclaredRead(List<SqlLexer.Token> tokens,
                                                           Set<String> declared, Set<String> schemas) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!isQualifiedNameAt(tokens, i)) continue;
            String schema = tokens.get(i).text().toLowerCase(Locale.ROOT);
            if (!schemas.contains(schema)) continue;   // alias.column / other schema — not a scope-table read
            String fqn = schema + "." + tokens.get(i + 2).text().toLowerCase(Locale.ROOT);
            if (!declared.contains(fqn)) {
                return Optional.of(new Violation(
                    tokens.get(i).text() + "." + tokens.get(i + 2).text(), List.copyOf(declared)));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code a.b} where {@code a} is not a cast target and {@code b} is not the start of a
     * {@code schema.fn(} function call. Deliberately matches every {@code a.b} pair in a longer dotted
     * chain, including the middle of one — {@code db.schema.table} yields both the {@code db.schema} pair
     * (skipped downstream, {@code db} is never a declared schema) and the trailing {@code schema.table}
     * pair, which is the read that matters. The declared-schema filter downstream is what keeps this from
     * over-blocking {@code schema.table.column}: at the {@code table.column} position, {@code table} is
     * not itself a declared schema, so it is filtered like any other {@code alias.column}.
     */
    private static boolean isQualifiedNameAt(List<SqlLexer.Token> tokens, int i) {
        if (tokens.get(i).kind() != SqlLexer.Kind.IDENT) return false;
        if (!isPunct(tokens.get(i + 1), ".")) return false;
        if (tokens.get(i + 2).kind() != SqlLexer.Kind.IDENT) return false;
        SqlLexer.Token prev = i > 0 ? tokens.get(i - 1) : null;
        if (prev != null && isOp(prev, "::")) return false;
        return !(i + 3 < tokens.size() && isPunct(tokens.get(i + 3), "("));
    }

    private static boolean isPunct(SqlLexer.Token t, String s) {
        return t.kind() == SqlLexer.Kind.PUNCT && t.text().equals(s);
    }

    private static boolean isOp(SqlLexer.Token t, String s) {
        return t.kind() == SqlLexer.Kind.OP && t.text().equals(s);
    }
}

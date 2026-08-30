package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Refuses a generated query that filters a column on a literal NOT in that column's declared
 * value-domain ({@link SchemaCard.Column#allowedValues()}). This blocks the cardinal sin of enterprise
 * RAG: a query that returns 0 rows for the wrong reason, then phrased as a confident "there are none."
 *
 * <p><b>High-confidence + fail-open.</b> Only clean {@code col = 'lit'} / {@code col <> 'lit'} /
 * {@code col IN ('a','b')} comparisons are inspected (exact, case-sensitive match of the literal against
 * the domain). A column wrapped in a function/cast (e.g. {@code lower(col) = '...'}) is NOT flagged —
 * better to miss one than to block a legitimate query on a parsing glitch. String literals become
 * single tokens (with {@code ''} escapes handled), so a column name appearing inside another column's
 * literal can never trigger a spurious refusal. Quoted-identifier filters ({@code "col" = '...'})
 * are deliberately not inspected (a miss, not an over-block) — the SchemaCard advertises bare names.
 *
 * <p>A bare {@code col} cannot be resolved to a specific table without parsing FROM/JOIN/aliases, so a
 * column NAME is only enforced when it has ONE consistent domain across the whole card. A name with
 * divergent domains across tables (or declared in one table and undeclared in another) is ambiguous and
 * never enforced — again, fail-open over over-blocking.
 */
@ApplicationScoped
public class DomainGuard {

    /** A filter literal that is not a declared value of the column. */
    public record Violation(String column, String value, List<String> allowed) {
        public String message() {
            // SQL-style escape the value so a literal containing a quote stays unambiguous.
            return "I won't run this: '" + value.replace("'", "''") + "' is not a declared value of " + column
                + " (known: " + String.join(", ", allowed)
                + "). If it should exist, add it to the entity manifest.";
        }
    }

    /**
     * Resolve which column names carry an enforceable domain, then check every literal filter against
     * it. (S3776: a card fold and a filter scan living in one method.)
     */
    public Optional<Violation> check(String sql, SchemaCard card) {
        if (sql == null || card == null) return Optional.empty();
        Map<String, Set<String>> domainByName = enforceableDomains(card);
        if (domainByName.isEmpty()) return Optional.empty();
        return firstOutOfDomainValue(sql, domainByName);
    }

    /**
     * Each column NAME resolved to its (consistent) domain across the whole card. A name seen with two
     * different domains — or declared in one table and undeclared in another — is ambiguous for a bare
     * reference, so it is dropped and never enforced. Order preserved by insertion.
     */
    private static Map<String, Set<String>> enforceableDomains(SchemaCard card) {
        Map<String, Set<String>> domainByName = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (SchemaCard.Table t : card.tables()) {
            for (SchemaCard.Column c : t.columns()) {
                if (ambiguous.contains(c.name())) continue;
                List<String> allowed = c.allowedValues();
                Set<String> dom = (allowed == null || allowed.isEmpty()) ? null : new LinkedHashSet<>(allowed);
                if (!domainByName.containsKey(c.name())) {
                    domainByName.put(c.name(), dom);
                } else if (!Objects.equals(domainByName.get(c.name()), dom)) {
                    ambiguous.add(c.name());
                    domainByName.remove(c.name());
                }
            }
        }
        domainByName.values().removeIf(Objects::isNull);
        return domainByName;
    }

    /**
     * Case-insensitive lookup that reports the DECLARED name (the old regex used {@code (?i)} and
     * reported the declared spelling).
     */
    private static Optional<Violation> firstOutOfDomainValue(String sql, Map<String, Set<String>> domainByName) {
        Map<String, String> declaredByLower = new HashMap<>();
        for (String name : domainByName.keySet()) declaredByLower.put(name.toLowerCase(Locale.ROOT), name);

        for (SqlFilters.Filter f : SqlFilters.columnLiteralFilters(SqlLexer.tokenize(sql))) {
            String declared = declaredByLower.get(f.column().toLowerCase(Locale.ROOT));
            if (declared == null) continue;                     // not a column with an enforceable domain
            Set<String> domain = domainByName.get(declared);
            List<String> allowed = new ArrayList<>(domain);
            for (String value : f.values()) {
                if (!domain.contains(value)) return Optional.of(new Violation(declared, value, allowed));
            }
        }
        return Optional.empty();
    }
}

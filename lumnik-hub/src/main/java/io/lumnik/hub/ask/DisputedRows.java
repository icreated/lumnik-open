package io.lumnik.hub.ask;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.extraction.spi.ask.Disputed;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlLexer;
import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.view.ColumnKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * #874 — the fused records an analytic answer filtered on while their sources disagree. Pure:
 * no database, no model. {@link #plan} decides from the guarded SQL whether a probe applies, and
 * whether a COUNTED entry may be reported (membership); the probe itself runs in
 * {@code AskToSqlAgent}. Anything this class does not recognise yields no plan — a missed warning,
 * never a false one (the fail-open posture of the other ask guards).
 */
final class DisputedRows {

    private DisputedRows() {}

    /**
     * {@code column = any of values}. {@code instants}: the column holds a date or a timestamp, so it is
     * equal as an instant, cast by the database as the question's own query casts it (#1326 review).
     */
    record Filter(String column, List<String> values, boolean instants) {
        Filter(String column, List<String> values) {
            this(column, values, false);
        }
    }

    /** When a COUNTED entry may be reported: never, once any row was read, or only while the read stayed under the cap. */
    enum Counted { NEVER, IF_ANY_ROW, IF_UNDER_CAP }

    record Plan(String view, String entity, List<Filter> filters, Counted counted) {
        boolean countedReportable(int rowCount, int maxRows) {
            return switch (counted) {
                case NEVER -> false;
                case IF_ANY_ROW -> rowCount > 0;
                case IF_UNDER_CAP -> rowCount < maxRows;
            };
        }
    }

    private static final Set<String> REFUSED = Set.of(
        "join", "union", "intersect", "except", "or", "not", "like", "ilike", "similar", "between", "is");
    private static final Set<String> CLAUSE_END = Set.of("group", "order", "limit", "offset", "having", "fetch");
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    static Optional<Plan> plan(String rawSql, String safeSql, SchemaCard card) {
        if (card == null || safeSql == null) return Optional.empty();
        List<Token> t = SqlLexer.tokenize(safeSql);
        if (count(t, "select") != 1 || count(t, "from") != 1) return Optional.empty();
        for (Token tok : t) if (isWord(tok) && REFUSED.contains(lower(tok))) return Optional.empty();

        int from = indexOf(t, "from", 0);
        if (from + 3 >= t.size() || !isIdent(t.get(from + 1)) || !isPunct(t.get(from + 2), ".") || !isIdent(t.get(from + 3))) {
            return Optional.empty();
        }
        String view = lower(t.get(from + 1)) + "." + lower(t.get(from + 3));
        String entity = EntityViewCompiler.entityNameFromView(view);
        Optional<SchemaCard.Table> table = card.tables().stream()
            .filter(x -> x.name().equalsIgnoreCase(view)).findFirst();
        if (entity == null || table.isEmpty()) return Optional.empty();

        // An optional table alias: AS ident, or a bare ident that is not a keyword.
        int where = from + 4;
        String qualifier = lower(t.get(from + 3));
        if (where < t.size() && isKeyword(t.get(where), "as") && where + 1 < t.size() && isIdent(t.get(where + 1))) {
            qualifier = lower(t.get(where + 1));
            where += 2;
        } else if (where < t.size() && isIdent(t.get(where)) && !isWord(t.get(where))) {
            qualifier = lower(t.get(where));
            where++;
        } else if (where < t.size() && isWord(t.get(where)) && !isKeyword(t.get(where), "where")
            && !CLAUSE_END.contains(lower(t.get(where)))) {
            qualifier = lower(t.get(where));
            where++;
        }
        if (where >= t.size() || !isKeyword(t.get(where), "where")) return Optional.empty();
        int end = where + 1;
        while (end < t.size() && !(isWord(t.get(end)) && CLAUSE_END.contains(lower(t.get(end))))) end++;

        Optional<List<Lexed>> filters = conjunction(t.subList(where + 1, end), qualifier);
        if (filters.isEmpty()) return Optional.empty();
        Map<String, String> declared = new HashMap<>();
        for (SchemaCard.Column c : table.get().columns()) declared.put(c.name().toLowerCase(Locale.ROOT), c.pgType());
        List<Filter> kept = new ArrayList<>();
        for (Lexed f : filters.get()) {
            if (!declared.containsKey(f.column())) return Optional.empty();
            ColumnKind kind = ColumnKind.ofType(declared.get(f.column()));
            List<String> values = f.values();
            if (kind.number()) {
                // #1338: said as the view and its ledger write a number (trim_scale), quoted or not — and a value that
                // is not a number makes no plan: the probe compares numbers, and a cast error is never a warning.
                values = values.stream().map(SqlFilters::canonicalNumber).toList();
                if (values.contains(null)) return Optional.empty();
            } else if (f.numbers()) {
                return Optional.empty();
            }
            kept.add(new Filter(f.column(), values, kind.temporal()));
        }

        return Optional.of(new Plan(table.get().name(), entity, kept,
            counted(rawSql, t, from)));
    }

    /** One filter as written: its values, and whether they were number literals rather than strings (#1338). */
    private record Lexed(String column, List<String> values, boolean numbers) {}

    /** {@code col = 'v'} / {@code col IN ('a', …)}, or the same with number literals, joined by AND only,
     *  consuming every token. A column may be qualified by {@code qualifier} (the alias, or the view name)
     *  and by nothing else. A list mixing strings and numbers is not clean. */
    private static Optional<List<Lexed>> conjunction(List<Token> c, String qualifier) {
        List<Lexed> out = new ArrayList<>();
        int i = 0;
        while (true) {
            if (i >= c.size() || !isIdent(c.get(i))) return Optional.empty();
            if (i + 2 < c.size() && isPunct(c.get(i + 1), ".")) {
                if (!isIdent(c.get(i + 2)) || !lower(c.get(i)).equals(qualifier)) return Optional.empty();
                i += 2;
            }
            String col = lower(c.get(i));
            if (i + 2 < c.size() && isOp(c.get(i + 1), "=") && literal(c.get(i + 2))) {
                out.add(new Lexed(col, List.of(c.get(i + 2).text()), c.get(i + 2).kind() == Kind.NUMBER));
                i += 3;
            } else if (i + 2 < c.size() && isKeyword(c.get(i + 1), "in") && isPunct(c.get(i + 2), "(")) {
                // LIT (, LIT)* then ')', every LIT of the first one's kind — rejects ('a',), ('a' 'b'), (1, 'a')
                // and an unclosed list.
                List<String> values = new ArrayList<>();
                int j = i + 3;
                Kind kind = j < c.size() ? c.get(j).kind() : null;
                while (true) {
                    if (j >= c.size() || !literal(c.get(j)) || c.get(j).kind() != kind) return Optional.empty();
                    values.add(c.get(j).text());
                    j++;
                    if (j < c.size() && isPunct(c.get(j), ",")) { j++; continue; }
                    break;
                }
                if (j >= c.size() || !isPunct(c.get(j), ")")) return Optional.empty();
                out.add(new Lexed(col, List.copyOf(values), kind == Kind.NUMBER));
                i = j + 1;
            } else {
                return Optional.empty();
            }
            if (i == c.size()) return Optional.of(out);
            if (!isKeyword(c.get(i), "and")) return Optional.empty();
            i++;
        }
    }

    private static boolean literal(Token t) {
        return t.kind() == Kind.STRING || t.kind() == Kind.NUMBER;
    }

    private static Counted counted(String rawSql, List<Token> t, int from) {
        boolean having = indexOf(t, "having", 0) >= 0;
        if (having) return Counted.NEVER;
        boolean grouped = indexOf(t, "group", 0) >= 0;
        List<Token> raw = SqlLexer.tokenize(rawSql == null ? "" : rawSql);
        boolean offset = indexOf(raw, "offset", 0) >= 0;
        if (!grouped && aggregateOnly(t.subList(1, from))) return offset ? Counted.NEVER : Counted.IF_ANY_ROW;
        boolean modelBound = offset || indexOf(raw, "limit", 0) >= 0 || indexOf(raw, "fetch", 0) >= 0;
        return modelBound ? Counted.NEVER : Counted.IF_UNDER_CAP;
    }

    /** Every top-level item of the select list is {@code agg(…)} with an optional alias. */
    private static boolean aggregateOnly(List<Token> select) {
        int i = 0;
        while (i < select.size()) {
            if (!isWord(select.get(i)) || !AGGREGATES.contains(lower(select.get(i)))) return false;
            if (i + 1 >= select.size() || !isPunct(select.get(i + 1), "(")) return false;
            int depth = 0, j = i + 1;
            for (; j < select.size(); j++) {
                if (isPunct(select.get(j), "(")) depth++;
                else if (isPunct(select.get(j), ")") && --depth == 0) break;
            }
            if (j >= select.size()) return false;
            i = j + 1;
            if (i < select.size() && isKeyword(select.get(i), "as")) i += 2;
            else if (i < select.size() && isIdent(select.get(i))) i += 1;
            if (i < select.size()) {
                if (!isPunct(select.get(i), ",")) return false;
                i++;
            }
        }
        return !select.isEmpty();
    }

    private static int count(List<Token> t, String word) {
        int n = 0;
        for (Token tok : t) if (isKeyword(tok, word)) n++;
        return n;
    }

    private static int indexOf(List<Token> t, String word, int from) {
        for (int i = from; i < t.size(); i++) if (isKeyword(t.get(i), word)) return i;
        return -1;
    }

    private static boolean isIdent(Token t) { return t.kind() == Kind.IDENT; }
    private static boolean isWord(Token t) { return t.kind() == Kind.IDENT && !t.quoted(); }
    private static boolean isKeyword(Token t, String w) { return isWord(t) && t.text().equalsIgnoreCase(w); }
    private static boolean isPunct(Token t, String s) { return t.kind() == Kind.PUNCT && t.text().equals(s); }
    private static boolean isOp(Token t, String s) { return t.kind() == Kind.OP && t.text().equals(s); }
    private static String lower(Token t) { return t.text().toLowerCase(Locale.ROOT); }

    static final int PROBE_LIMIT = 11;
    static final int CAP = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Probe(String sql, List<Object> params) {}
    record ProbeRow(String key, String retained, String ledgerJson) {}
    record Outcome(List<Disputed> disputed, boolean truncated) {}

    /** The record key a probe can name: {@code match:} columns; a {@code resolution:} entity has none (#613). */
    static Optional<List<String>> keyColumns(EntityManifest m) {
        if (m == null || m.resolution() != null || m.match() == null || m.match().isEmpty()) return Optional.empty();
        return Optional.of(List.copyOf(m.match()));
    }

    /**
     * The filters worth a probe (#882): a {@code match:} column is the record's identity, never in the
     * ledger, so its probe is always empty — and fuses the whole entity to say so. {@link #probe}
     * still keeps it in the WHERE of the others, where Postgres can push it below the fusion.
     */
    static List<Filter> probed(Plan plan, List<String> keyColumns) {
        Set<String> keys = new HashSet<>();
        for (String k : keyColumns) keys.add(k.toLowerCase(Locale.ROOT));
        return plan.filters().stream().filter(f -> !keys.contains(f.column())).toList();
    }

    /**
     * One bounded read for filtered column {@code f}: records that pass the plan's OTHER filters,
     * carry a ledger entry for {@code f}, and either kept a filtered value or have a source that holds
     * one — only the latter (excluded candidates) when {@code countedAdmissible} is false. Excluded
     * candidates sort first, then by key. Relevance is decided here, in SQL, so the LIMIT bounds relevant rows only. Identifiers come
     * from the plan (the card); every value is a bound parameter.
     */
    static Probe probe(Plan plan, Filter f, List<String> keyColumns, boolean countedAdmissible) {
        return probe(plan, f, keyColumns, countedAdmissible, Optional.empty());
    }

    /**
     * {@code relation} (#965), when the entity allows one, replaces the view in the FROM: the same
     * columns, fused only for the records the probe can pass. The predicate, the ordering and the
     * LIMIT below are the same either way — {@code DisputedProbeEquivalenceIT} holds them to it.
     */
    static Probe probe(Plan plan, Filter f, List<String> keyColumns, boolean countedAdmissible,
                       Optional<EntityViewCompiler.ProbeRelation> relation) {
        if (keyColumns.isEmpty()) throw new IllegalArgumentException("a probe needs at least one key column");
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < keyColumns.size(); i++) {
            sql.append(quoteIdent(keyColumns.get(i))).append("::text AS ").append(quoteIdent("_k" + i)).append(", ");
        }
        sql.append(quoteIdent(f.column())).append("::text AS \"_retained\", (\"_conflicts\" -> ?)::text AS \"_ledger\"");
        params.add(f.column());
        if (relation.isPresent()) {
            sql.append("\nFROM ").append(relation.get().sql()).append(" probe");
            for (String column : relation.get().paramColumns()) {
                params.add(plan.filters().stream().filter(x -> x.column().equals(column)).findFirst().orElseThrow().values());
            }
        } else {
            sql.append("\nFROM ").append(qualified(plan.view()));
        }
        sql.append("\nWHERE ");
        for (Filter o : plan.filters()) {
            if (o.column().equals(f.column())) continue;
            sql.append(equal(quoteIdent(o.column()), o)).append(" AND ");
            params.add(o.values());
        }
        sql.append("jsonb_typeof(\"_conflicts\" -> ?) = 'array'");
        params.add(f.column());
        String retainedMatches = "(" + equal(quoteIdent(f.column()), f) + ")";
        String sourceHolds = "EXISTS (SELECT 1 FROM jsonb_array_elements(\"_conflicts\" -> ?) e WHERE "
            + (f.instants() ? equal("(e ->> 'value')", f) : "e ->> 'value' = ANY(?)") + ")";
        // Counted entries that cannot be reported must not take LIMIT slots: the overflow flag then
        // counts admissible rows only, so truncation is never claimed over entries nobody will see.
        if (countedAdmissible) sql.append("\n  AND (").append(retainedMatches).append(" OR ").append(sourceHolds).append(")");
        else sql.append("\n  AND ").append(retainedMatches).append(" IS NOT TRUE AND ").append(sourceHolds);
        params.add(f.values());
        params.add(f.column());
        params.add(f.values());
        // Excluded first (retained false or NULL), then by key: the worse harm is never crowded out
        // of the cap by counted entries that happen to sort earlier.
        sql.append("\nORDER BY ").append(retainedMatches).append(" IS TRUE");
        params.add(f.values());
        for (String k : keyColumns) sql.append(", ").append(quoteIdent(k));
        sql.append("\nLIMIT ").append(PROBE_LIMIT);
        return new Probe(sql.toString(), List.copyOf(params));
    }

    /** {@code expr} against the filter's bound values: as instants on a date column, as the exact text otherwise. */
    private static String equal(String expr, Filter f) {
        return expr + (f.instants() ? "::timestamptz = ANY(?::timestamptz[])" : "::text = ANY(?)");
    }

    /**
     * Whether {@code value} is one the filter names. A date is compared as an instant; a literal the platform's
     * reader cannot place (PostgreSQL's {@code today}) matches nothing — a missed warning, never a false one.
     */
    private static boolean named(Filter f, String value) {
        if (value == null) return false;
        if (!f.instants()) return f.values().contains(value);
        Instant at = TemporalText.parse(value);
        return at != null && f.values().stream().map(TemporalText::parse).anyMatch(at::equals);
    }

    static Optional<Disputed> classify(ProbeRow row, Filter f, String label) {
        List<Disputed.SourceValue> sources = new ArrayList<>();
        try {
            JsonNode ledger = JSON.readTree(row.ledgerJson() == null ? "[]" : row.ledgerJson());
            if (!ledger.isArray()) return Optional.empty();
            for (JsonNode e : ledger) {
                JsonNode v = e.get("value");
                sources.add(new Disputed.SourceValue(e.path("src").asText(null),
                    v == null || v.isNull() ? null : v.asText()));
            }
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (named(f, row.retained())) {
            return Optional.of(new Disputed(row.key(), f.column(), label, row.retained(), row.retained(), true, sources));
        }
        for (Disputed.SourceValue s : sources) {
            if (named(f, s.value())) {
                return Optional.of(new Disputed(row.key(), f.column(), label, s.value(), row.retained(), false, sources));
            }
        }
        return Optional.empty();
    }

    static Outcome assemble(List<Disputed> found, boolean overflowed, boolean countedReportable) {
        List<Disputed> excluded = new ArrayList<>();
        List<Disputed> counted = new ArrayList<>();
        boolean truncated = overflowed;
        for (Disputed d : found) (d.counted() ? counted : excluded).add(d);
        if (!countedReportable) counted.clear();
        List<Disputed> all = new ArrayList<>(excluded);
        all.addAll(counted);
        if (all.size() > CAP) {
            truncated = true;
            all = all.subList(0, CAP);
        }
        // No block renders for an empty list, so a lone flag would say nothing anyone can see.
        return new Outcome(List.copyOf(all), truncated && !all.isEmpty());
    }

    private static String qualified(String view) {
        int dot = view.indexOf('.');
        return quoteIdent(view.substring(0, dot)) + "." + quoteIdent(view.substring(dot + 1));
    }
}

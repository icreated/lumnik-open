package io.lumnik.hub.process;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.ManagedIndex.Spec;

import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * One way a change in a lookup table reaches the fused rows of an Entity (#1834): a {@code joins:} entry, seen from one of
 * the Entity's sources. A lookup row's key selects the rows of the source whose {@code from} value meets it, and each of
 * those rows is the match key of a fused row to evaluate.
 *
 * <p>The keys are written by {@link EntityViewCompiler}, the functions the view itself joins by: a reader that
 * compared another way would reach rows the view does not fuse, and miss rows it does. {@link #fromKey} is also the
 * expression of the index the hub manages for the reverse read ({@link #index}), so the planner can use it.
 *
 * @param target which reach this is, stable across events: the work rows are unique on it (one lookup event can match
 *               several joins of an Entity, and a join can be read from several sources)
 * @param lookupTable the lookup, schema-qualified as an event names it
 * @param sourceTable the source's bare table name
 * @param matchKeys the source's match key, column by column, as the view groups it
 * @param fromKey the source's join key, as the view compares it
 * @param toKey the lookup's join key, as the view compares it
 * @param keyType the SQL type {@link #toKey}'s value is bound as ({@code text}, {@code date}, {@code timestamptz})
 */
record LookupReach(String target, String lookupTable, String sourceTable, List<String> matchKeys, String fromKey,
                   String toKey, String keyType) {

    /** Every reach of an Entity: each join from each source that maps the join's {@code from} and the whole match key. */
    static List<LookupReach> of(EntityManifest m, EntityViewCompiler.Sources catalog) {
        List<LookupReach> out = new ArrayList<>();
        if (m.resolution() != null) return out;
        for (EntityManifest.Source s : m.sources()) {
            if (m.match().stream().anyMatch(c -> EntityViewCompiler.sourceColumn(m, s, c) == null)) continue;   // fuses nothing
            List<String> matchKeys = EntityViewCompiler.matchKeyExpressions(m, catalog, s.table());
            for (EntityManifest.Join j : m.joins()) {
                String from = EntityViewCompiler.sourceColumn(m, s, j.from());
                if (from == null) continue;
                out.add(new LookupReach(m.name() + ":" + s.table() + ":" + j.table() + ":" + j.from() + ">" + j.to(),
                    EntityViewCompiler.SCHEMA + "." + j.table(), s.table(), matchKeys,
                    EntityViewCompiler.lookupFromKey(m, catalog, j, s.table(), from, ""),
                    EntityViewCompiler.lookupToKey(m, catalog, j), EntityViewCompiler.lookupKeyType(m, catalog, j)));
            }
        }
        return out;
    }

    /** The index that serves the reverse read: the tenant, then the join key — the one predicate the read has. */
    Spec index() {
        return new Spec(EntityViewCompiler.SCHEMA, sourceTable, List.of("tenant_id", fromKey));
    }

    /**
     * The match key's canonical form, in SQL: {@code MatchKeyEncoding.encode} written as an expression, so the order of
     * the keys is the database's (the cursor is a keyset on it) and the value is the one the view's {@code _canonical_key}
     * holds. {@code COLLATE "C"} makes the order the bytes', whatever the database's locale.
     */
    private String canonical() {
        StringBuilder sql = new StringBuilder("('").append(matchKeys.size()).append("'");
        for (String k : matchKeys) {
            sql.append(" || '|' || octet_length((").append(k).append(")::text) || ':' || (").append(k).append(")::text");
        }
        return sql.append(") COLLATE \"C\"").toString();
    }

    private String where() {
        StringBuilder sql = new StringBuilder(" FROM ").append(EntityViewCompiler.SCHEMA).append('.').append(quoteIdent(sourceTable))
            .append(" WHERE tenant_id = ? AND ").append(fromKey).append(" = ?::").append(keyType);
        for (String k : matchKeys) sql.append(" AND (").append(k).append(") IS NOT NULL");
        return sql.toString();
    }

    /** How many fused rows the lookup key reaches: binds the tenant, then the key. */
    String countSql() {
        return "SELECT count(*) FROM (SELECT DISTINCT " + canonical() + where() + ") k";
    }

    /**
     * One page of the keys the lookup key reaches, after the cursor, in key order: {@code ck} then each match value as
     * text. Binds the tenant, the key, (the cursor,) the page size. The read is by {@link #fromKey} under an explicit
     * tenant, on the admin pool: what it finds is candidates, and the view read — under RLS — decides the truth.
     */
    String pageSql(boolean afterCursor) {
        StringBuilder select = new StringBuilder("SELECT ").append(canonical()).append(" AS ck");
        for (String k : matchKeys) select.append(", (").append(k).append(")::text");
        return "SELECT DISTINCT * FROM (" + select + where() + ") k" + (afterCursor ? " WHERE ck > ?" : "")
            + " ORDER BY ck LIMIT ?";
    }
}

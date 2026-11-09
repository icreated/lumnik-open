package io.lumnik.hub.sql;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * An index the hub builds for a runtime read (#1838), as {@code IngestedAtIndex} does for the mark producer's (#945):
 * {@code CREATE INDEX CONCURRENTLY} on an autocommit admin connection, a session advisory lock between nodes, an
 * invalid index (an interrupted build) dropped and left to the next pass, and no automatic DROP.
 *
 * <p>The index is whatever {@link Spec} says: a list of key expressions, so a reader that filters on
 * {@code trim_scale(col::numeric)} is served by an index on exactly that — a plain {@code (tenant_id, col)} would
 * not be used. The name carries a digest of the expressions, so a key that changes is a new index, not a collision
 * with the old one; "is it ours" is then a check of the table and the key count, never of a name alone.
 *
 * <p>Best effort at run time: the read is correct without the index, only slow. Callers log a failure and carry on.
 */
@ApplicationScoped
public class ManagedIndex {

    private static final Logger LOG = Logger.getLogger(ManagedIndex.class);
    /** PostgreSQL silently truncates identifiers beyond this; a truncated name would never be found again. */
    private static final int MAX_IDENT_BYTES = 63;
    private static final String INFIX = "_mx_";

    @Inject @DataSource("admin") AgroalDataSource ds;

    /**
     * @param keys the index's key, one SQL expression per column ({@code tenant_id}, {@code trim_scale(c::numeric)}…);
     *             each is parenthesised when the index is created, so a cast is as valid as a column
     */
    public record Spec(String schema, String table, List<String> keys) {

        /** {@code table_mx_<digest of the table and the keys>}, cut at the table so the digest always fits. */
        public String name() {
            String suffix = INFIX + IndexBuilds.digest(schema + "." + table + "|" + String.join("|", keys));
            String base = table;
            while ((base + suffix).getBytes(StandardCharsets.UTF_8).length > MAX_IDENT_BYTES) {
                base = base.substring(0, base.length() - 1);
            }
            return base + suffix;
        }

        public String qualifiedTable() {
            return schema + "." + quoteIdent(table);
        }

        String keyList() {
            return keys.stream().map(k -> "(" + k + ")").collect(Collectors.joining(", "));
        }
    }

    public enum State { VALID, PENDING }

    /** {@link State#VALID} when the index is there and usable; otherwise it is still to be built (or rebuilt). */
    public State state(Spec spec) {
        return existing(spec).map(e -> e.valid() && ours(spec, e) ? State.VALID : State.PENDING).orElse(State.PENDING);
    }

    /** Whether the table is there: an index waits for a table that is not (an Entity may be applied before its first ingestion). */
    public boolean tableExists(Spec spec) {
        return Jdbc.exists(ds, "ManagedIndex.tableExists", TABLE_EXISTS, ps -> ps.setString(1, spec.qualifiedTable()));
    }

    private static final String TABLE_EXISTS = "SELECT 1 WHERE to_regclass(?) IS NOT NULL";

    /** One pass: builds the index, or drops an invalid one (the next pass builds it). Quiet when the table is not there yet. */
    public void ensure(Spec spec) throws SQLException {
        String name = spec.name();
        String lockKey = spec.schema() + "." + name;
        if (state(spec) == State.VALID) return;   // the steady state: nothing to lock for
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            if (!IndexBuilds.tryLock(c, lockKey)) return; // another node is on it
            try {
                if (!Jdbc.exists(c, "ManagedIndex.tableExists", TABLE_EXISTS, ps -> ps.setString(1, spec.qualifiedTable()))) return;
                Optional<Existing> existing = existing(c, spec);
                if (existing.isEmpty()) {
                    Jdbc.update(c, "ManagedIndex.ensure create", "CREATE INDEX CONCURRENTLY IF NOT EXISTS "
                        + quoteIdent(name) + " ON " + spec.qualifiedTable() + " (" + spec.keyList() + ")", ps -> {});
                    // an expression has no statistics until the table is analysed again: without them the planner
                    // guesses its selectivity and may keep the sequential scan the index is there to replace
                    Jdbc.update(c, "ManagedIndex.ensure analyze", "ANALYZE " + spec.qualifiedTable(), ps -> {});
                } else if (!ours(spec, existing.get())) {
                    // Index names are unique per schema: an object under our name that is not our index (valid or not
                    // — it may be someone's own build in progress) would otherwise be taken for it forever.
                    LOG.warnf("%s.%s exists but is not the index managed for %s — leaving it", spec.schema(), name,
                        spec.qualifiedTable());
                } else if (!existing.get().valid()) {
                    LOG.warnf("%s.%s is invalid (an interrupted build?) — dropping it; the next pass rebuilds it",
                        spec.schema(), name);
                    Jdbc.update(c, "ManagedIndex.ensure drop invalid",
                        "DROP INDEX CONCURRENTLY IF EXISTS " + spec.schema() + "." + quoteIdent(name), ps -> {});
                }
            } finally {
                IndexBuilds.unlock(c, lockKey);
            }
        }
    }

    /**
     * Why this key cannot serve a read, or empty when it can (or when the question is not ours: a table or a column that
     * is not there yet). PostgreSQL decides — the key is tried on an empty copy of the table, in a transaction that
     * leaves nothing behind — so no list of "good expressions" can drift from the server's. Two ways to be refused:
     * <ul>
     *   <li>PostgreSQL cannot index it: a function in the key is not IMMUTABLE ({@code timestamptz::text} depends on the
     *       session's zone), SQLSTATE {@code 42P17};</li>
     *   <li>PostgreSQL would not use it: the reader runs under row-level security, and a predicate that calls a function
     *       that is not LEAKPROOF ({@code trim_scale}, {@code timezone}, a cast to {@code numeric}) is evaluated after the
     *       policy, never as an index condition — the build would cost its space and the read would still scan.</li>
     * </ul>
     * The words name the table and the functions, never a value.
     */
    public Optional<String> refusal(Spec spec) {
        return refusal(spec, true);
    }

    /**
     * @param underRls whether the reader runs under row-level security. A reader on the {@code admin} pool (BYPASSRLS, the
     *                 tenant in its own predicate) is served by any index PostgreSQL can build: only the IMMUTABLE rule
     *                 applies to it, not the LEAKPROOF one.
     */
    public Optional<String> refusal(Spec spec, boolean underRls) {
        if (!tableExists(spec)) return Optional.empty();
        String verdictKey = spec.name() + (underRls ? "" : "|admin");
        Optional<String> known = refusals.get(verdictKey);   // the name digests the key: the verdict cannot change
        if (known != null) return known;
        String table = spec.schema() + "." + spec.table();
        try {
            return remember(verdictKey, Jdbc.transaction(ds, "ManagedIndex.refusal", c -> {
                Jdbc.update(c, "ManagedIndex.refusal copy",
                    "CREATE TEMP TABLE managed_index_probe ON COMMIT DROP AS SELECT * FROM " + spec.qualifiedTable()
                        + " WITH NO DATA", ps -> {});
                Jdbc.update(c, "ManagedIndex.refusal index",
                    "CREATE INDEX managed_index_probe_idx ON managed_index_probe (" + spec.keyList() + ")", ps -> {});
                if (!underRls) return Optional.<String>empty();
                // the functions the key calls, read off the stored expression tree (a built-in function is a pinned object:
                // pg_depend does not record it)
                return Jdbc.queryOne(c, "ManagedIndex.refusal leakproof",
                    "SELECT string_agg(DISTINCT p.proname, ', ' ORDER BY p.proname) FROM pg_index i, "
                  + "regexp_matches(i.indexprs::text, ':(?:op)?funcid ([0-9]+)', 'g') m "
                  + "JOIN pg_proc p ON p.oid = m[1]::oid "
                  + "WHERE i.indexrelid = 'pg_temp.managed_index_probe_idx'::regclass AND NOT p.proleakproof",
                    ps -> {}, rs -> rs.getString(1))
                    .map(functions -> "an index on " + table + " cannot serve this key: it is read through " + functions
                        + ", and under row-level security PostgreSQL does not let an index serve a call to it");
            }));
        } catch (RuntimeException e) {
            if (!"42P17".equals(Jdbc.sqlStateOf(e))) return Optional.empty();   // not the key's fault: ask again next time
            return remember(verdictKey, Optional.of("an index on " + table + " cannot serve this key: it is read through a function"
                + " whose result depends on the session (a time zone, a setting), which PostgreSQL does not index"));
        }
    }

    private final Map<String, Optional<String>> refusals = new ConcurrentHashMap<>();
    private final Map<String, List<String>> expectedKeys = new ConcurrentHashMap<>();

    private Optional<String> remember(String verdictKey, Optional<String> verdict) {
        refusals.put(verdictKey, verdict);
        return verdict;
    }

    /** The index holding our name: {@code valid} is {@code indisvalid}; {@code plain} is "a non-unique, non-partial index
     *  on this very table"; {@code keys} is each key column as PostgreSQL prints it. */
    private record Existing(boolean valid, boolean plain, List<String> keys) { }

    /**
     * Ours is exactly the index asked for: plain, on this table, with every key as PostgreSQL itself prints the keys of
     * {@code spec} — never "something of this name with as many columns". An object that is not is left alone, valid or
     * not: it is not ours to accept or to drop.
     */
    private boolean ours(Spec spec, Existing e) {
        return e.plain() && e.keys().equals(expectedKeys(spec));
    }

    /** The keys of {@code spec} as PostgreSQL prints them, read off the same index built on an empty copy of the table. */
    private List<String> expectedKeys(Spec spec) {
        return expectedKeys.computeIfAbsent(spec.name(), n -> Jdbc.transaction(ds, "ManagedIndex.expectedKeys", c -> {
            Jdbc.update(c, "ManagedIndex.expectedKeys copy",
                "CREATE TEMP TABLE managed_index_expected ON COMMIT DROP AS SELECT * FROM " + spec.qualifiedTable()
                    + " WITH NO DATA", ps -> {});
            Jdbc.update(c, "ManagedIndex.expectedKeys index",
                "CREATE INDEX managed_index_expected_idx ON managed_index_expected (" + spec.keyList() + ")", ps -> {});
            return Jdbc.queryOne(c, "ManagedIndex.expectedKeys read", KEYS_OF + "'pg_temp.managed_index_expected_idx'::regclass",
                ps -> {}, ManagedIndex::keysOf).orElseThrow();
        }));
    }

    private static final String KEYS_OF = "SELECT array_agg(pg_get_indexdef(i.indexrelid, k, true) ORDER BY k) "
        + "FROM pg_index i, generate_series(1, i.indnkeyatts) k WHERE i.indexrelid = ";

    private Optional<Existing> existing(Spec spec) {
        return Jdbc.queryOne(ds, "ManagedIndex.existing", EXISTING, existingBinder(spec), ManagedIndex::mapExisting);
    }

    private static Optional<Existing> existing(Connection c, Spec spec) {
        return Jdbc.queryOne(c, "ManagedIndex.existing", EXISTING, existingBinder(spec), ManagedIndex::mapExisting);
    }

    private static final String EXISTING =
        "SELECT i.indisvalid, (i.indrelid = to_regclass(?) AND NOT i.indisunique AND i.indpred IS NULL), "
      + "(SELECT array_agg(pg_get_indexdef(i.indexrelid, k, true) ORDER BY k) FROM generate_series(1, i.indnkeyatts) k) "
      + "FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
      + "JOIN pg_namespace n ON n.oid = ic.relnamespace WHERE n.nspname = ? AND ic.relname = ?";

    private static Jdbc.Binder existingBinder(Spec spec) {
        return ps -> {
            ps.setString(1, spec.qualifiedTable());
            ps.setString(2, spec.schema());
            ps.setString(3, spec.name());
        };
    }

    private static Existing mapExisting(ResultSet rs) throws SQLException {
        return new Existing(rs.getBoolean(1), rs.getBoolean(2), List.of((String[]) rs.getArray(3).getArray()));
    }

    private static List<String> keysOf(ResultSet rs) throws SQLException {
        return List.of((String[]) rs.getArray(1).getArray());
    }
}

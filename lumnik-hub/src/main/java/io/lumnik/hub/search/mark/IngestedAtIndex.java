package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * #945: {@code (tenant_id, _ingested_at)} on a {@code connector.t_*} table a valid SearchIndex observes, so the
 * mark producer's {@code max(_ingested_at) ... WHERE tenant_id = ? AND _ingested_at > ?} stops reading every
 * row the tenant has in it on each pass.
 *
 * <p>Created by {@link SearchProjectionReconciler} the first time it sees the table, never with the table: the
 * write cost (one more index per insert, and {@code on-conflict: update} touches of {@code _ingested_at} that
 * can no longer be HOT) then lands only on tables Search observes, and tables that already exist are caught up
 * with no migration. There is no automatic DROP: an index nobody needs any more costs space and HOT, not
 * correctness.
 *
 * <p>{@code CREATE INDEX CONCURRENTLY} cannot run in a transaction (a plain build would block ingestion for as
 * long as it takes), so this runs on an autocommit admin connection. A build that is interrupted leaves an
 * <i>invalid</i> index that {@code IF NOT EXISTS} never repairs, so an invalid one is dropped and the rebuild
 * is left to the next tick. A session advisory lock serialises nodes: an invalid index is only
 * dropped while this node holds it, i.e. when no other node is mid-build (an in-progress build is invalid too).
 *
 * <p>Best effort: the scan is correct without the index, just slow, so callers log a failure and carry on.
 */
@ApplicationScoped
public class IngestedAtIndex {

    private static final Logger LOG = Logger.getLogger(IngestedAtIndex.class);
    private static final String SCHEMA = "connector";
    private static final String SUFFIX = "_ing_idx";
    /** PostgreSQL silently truncates identifiers beyond this; a truncated name would never be found again. */
    private static final int MAX_IDENT_BYTES = 63;

    @Inject @DataSource("admin") AgroalDataSource ds;

    /** @param table the unqualified {@code t_*} name, as {@link SearchProjectionRunHook#targetsByTable} keys it */
    void ensure(String table) throws SQLException {
        String index = indexName(table);
        String qualifiedTable = SCHEMA + "." + quoteIdent(table);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            if (!tryLock(c, index)) return; // another node is on it
            try {
                if (!tableExists(c, qualifiedTable)) return;
                Existing existing = existing(c, index, qualifiedTable);
                if (existing == null) {
                    try (Statement st = c.createStatement()) {
                        st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + quoteIdent(index)
                            + " ON " + qualifiedTable + " (tenant_id, _ingested_at)");
                    }
                } else if (!existing.ours()) {
                    // Index names are unique per schema: an object under our name that is not our index (valid
                    // or not -- it may be someone's own build in progress) would otherwise be taken for it
                    // forever, and it is not ours to drop.
                    LOG.warnf("%s.%s exists but is not a (tenant_id, _ingested_at) index on %s — leaving it; "
                        + "%s is scanned without an index", SCHEMA, index, table, table);
                } else if (!existing.valid()) {
                    LOG.warnf("%s.%s is invalid (an interrupted build?) — dropping it; the next tick rebuilds it",
                        SCHEMA, index);
                    try (Statement st = c.createStatement()) {
                        st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + SCHEMA + "." + quoteIdent(index));
                    }
                }
            } finally {
                unlock(c, index);
            }
        }
    }

    /** The table name plus a suffix; past PostgreSQL's identifier limit, a prefix plus a SHA-256 of the table name. */
    static String indexName(String table) {
        String name = table + SUFFIX;
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_IDENT_BYTES) return name;
        String digest = digest(table);
        String prefix = table.substring(0, MAX_IDENT_BYTES - digest.length() - SUFFIX.length() - 1);
        return prefix + "_" + digest + SUFFIX;
    }

    /** The first 8 bytes of SHA-256, hex-encoded (as {@code Identifiers.digest} does with 5). */
    private static String digest(String raw) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static boolean tryLock(Connection c, String index) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, SCHEMA + "." + index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, String index) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            ps.setString(1, SCHEMA + "." + index);
            ps.execute();
        }
    }

    private static boolean tableExists(Connection c, String qualifiedTable) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, qualifiedTable);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** The index holding our name: {@code valid} is {@code indisvalid}; {@code ours} is "a plain, non-partial
     *  (tenant_id, _ingested_at) index on this very table". */
    private record Existing(boolean valid, boolean ours) { }

    /** Null = no index holds the name. */
    private static Existing existing(Connection c, String index, String qualifiedTable) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT i.indisvalid, (i.indrelid = ?::regclass AND NOT i.indisunique AND i.indpred IS NULL "
              + "AND i.indnkeyatts = 2 AND i.indnatts = 2 AND (SELECT array_agg(a.attname ORDER BY k.ord) "
              + "FROM unnest(i.indkey::int2[]) WITH ORDINALITY k(attnum, ord) "
              + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) "
              + "= ARRAY['tenant_id', '_ingested_at']::name[]) "
              + "FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
              + "JOIN pg_namespace n ON n.oid = ic.relnamespace WHERE n.nspname = ? AND ic.relname = ?")) {
            ps.setString(1, qualifiedTable);
            ps.setString(2, SCHEMA);
            ps.setString(3, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Existing(rs.getBoolean(1), rs.getBoolean(2)) : null;
            }
        }
    }
}

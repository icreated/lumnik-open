package io.lumnik.hub.entity;

import io.lumnik.common.sql.Jdbc;

import java.sql.Connection;
import java.util.Locale;

/**
 * One transaction-scoped advisory lock per entity definition (#669), the {@code DynamicTableWriter}
 * idiom ({@code pg_advisory_xact_lock(hashtextextended(key, 0))}), released on commit or rollback.
 *
 * <p>Writes of the definition itself — {@code EntityViewCompiler} apply and delete — take it
 * EXCLUSIVE. Writes that validated against the definition and must not commit around a change to it
 * — a SearchIndex apply — take it SHARED and hold it to their commit. So an entity re-apply waits for
 * an in-flight SearchIndex write (and its re-check then sees that declaration), and a SearchIndex
 * write waiting on a compile re-reads the committed definition. An advisory lock needs no privilege on
 * {@code connector.entity_manifest} beyond what each side already has.
 */
public final class EntityDefinitionLock {

    private EntityDefinitionLock() {}

    static String key(String entityName) {
        return "connector.entity_manifest:" + entityName.toLowerCase(Locale.ROOT);
    }

    /** For a write OF the definition; blocks while any validated write over it is in flight. */
    public static void exclusive(Connection c, String entityName) {
        take(c, "EntityDefinitionLock.exclusive", "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", entityName);
    }

    /** For a write validated AGAINST the definition; blocks while the definition is being rewritten. */
    public static void shared(Connection c, String entityName) {
        take(c, "EntityDefinitionLock.shared", "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", entityName);
    }

    private static void take(Connection c, String label, String sql, String entityName) {
        // The lock is the statement's effect; its one (void) row is read and dropped.
        Jdbc.queryOne(c, label, sql, ps -> ps.setString(1, key(entityName)), rs -> true);
    }
}

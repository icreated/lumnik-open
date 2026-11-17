package io.lumnik.hub.entity;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * #2048: a connector writes a table only within the scopes of every entity reading it. Entity reads ask
 * {@code allowsAny}, so a writer lacking one of an entity's scopes would hand its rows to that scope's holders — the
 * rule {@code EntityViewCompiler} applies at apply, kept when the writers change afterwards:
 * <ul>
 *   <li>a connector's first write to a {@code connector} table ({@link #requireNewWriterWithinReaders}), before any
 *       row, on the write's connection — a writer already registered is not asked again, so a sharing in place keeps
 *       working;</li>
 *   <li>a connector's scopes replaced ({@link #requireRetagWithinReaders}): refused when an entity's scope the writer
 *       carried is dropped, so a sharing a tenant-wide integrator chose, or a re-apply that keeps the scopes, still
 *       passes, and moving the writer out of a scope its entity serves does not.</li>
 * </ul>
 * The readers are the entity views Postgres records as depending on the table ({@code pg_depend}): exact, and blind to
 * a stored manifest's text. An entity over another entity's view stays within that one's scopes (#2042), so the views
 * reading the table directly are all there is to ask. The refusal names no entity: the caller may hold none of its
 * scopes. A one-shot CSV writes {@code ext.*}, which no entity reads.
 */
public final class NewWriterScopes {

  /**
   * The scopes of each entity the tenant reads whose view reads one of the {@code connector} tables named (whole, or
   * as kept). Another tenant's entity is not this tenant's reader: RLS keeps its rows from it (#2050).
   */
  private static final String READERS = "SELECT m.scopes FROM pg_catalog.pg_class tc "
      + "JOIN pg_catalog.pg_namespace tns ON tns.oid = tc.relnamespace AND tns.nspname = 'connector' "
      + "JOIN pg_catalog.pg_depend d ON d.refobjid = tc.oid AND d.refclassid = 'pg_catalog.pg_class'::regclass "
      + "  AND d.classid = 'pg_catalog.pg_rewrite'::regclass AND d.deptype = 'n' "
      + "JOIN pg_catalog.pg_rewrite rw ON rw.oid = d.objid "
      + "JOIN pg_catalog.pg_class v ON v.oid = rw.ev_class AND v.relkind = 'v' AND v.oid <> tc.oid "
      + "JOIN pg_catalog.pg_namespace vns ON vns.oid = v.relnamespace AND vns.nspname = 'connector' "
      + "JOIN connector.entity_manifest m ON left('v_' || m.name, 63) = v.relname "   // as Postgres keeps a long name
      + "WHERE tc.relname IN (SELECT left(t, 63) FROM unnest(?::text[]) t) "
      + "AND (m.owner_tenant_id IS NULL OR m.owner_tenant_id = ?)";

  private NewWriterScopes() {
  }

  /** Refuses {@code connectorId}'s first write to {@code schema.table} when an entity reading it has a scope it lacks. */
  public static void requireNewWriterWithinReaders(Connection c, Long tenantId, String schema, String table,
                                                   UUID connectorId) {
    if (connectorId == null || tenantId == null || !EntityViewCompiler.SCHEMA.equals(schema)) return;
    boolean registered = Jdbc.exists(c, "NewWriterScopes.registered",
        "SELECT 1 FROM connector.dynamic_table WHERE tenant_id = ? AND table_schema = ? AND table_name = ? "
            + "AND connector_id = ?",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setString(2, schema);
          ps.setString(3, table);
          ps.setObject(4, connectorId);
        });
    if (registered) return;
    record Writer(String name, List<String> tags) {}
    // FOR SHARE: a re-tag holds the row FOR UPDATE until its commit, so these are its scopes once it has decided
    Jdbc.queryOne(c, "NewWriterScopes.writer",
            "SELECT name, tags FROM connector.connector WHERE id = ? AND tenant_id = ? FOR SHARE",
            ps -> {
              ps.setObject(1, connectorId);
              ps.setLong(2, tenantId);
            },
            rs -> new Writer(rs.getString(1), EntityRegistry.scopes(rs.getArray(2))))
        .ifPresent(w -> {
          if (readers(c, tenantId, List.of(table)).stream().anyMatch(s -> !w.tags().containsAll(s))) {
            throw refused(w.name(), "connector." + table);
          }
        });
  }

  /**
   * Refuses replacing {@code connectorId}'s scopes {@code before} with {@code after} when an entity reading a table it
   * writes has a scope the old ones carried and the new ones drop: the entity's missing scopes may not grow.
   */
  public static void requireRetagWithinReaders(DataSource admin, long tenantId, UUID connectorId, String connector,
                                               Collection<String> before, Collection<String> after) {
    if (after.containsAll(before)) return;   // dropping no scope breaches nothing: a re-apply keeping them asks nothing
    Jdbc.transaction(admin, "NewWriterScopes.requireRetagWithinReaders", c -> {
      List<String> written = Jdbc.query(c, "NewWriterScopes.written",
          "SELECT DISTINCT table_name FROM connector.dynamic_table WHERE connector_id = ? AND table_schema = ?",
          ps -> {
            ps.setObject(1, connectorId);
            ps.setString(2, EntityViewCompiler.SCHEMA);
          },
          rs -> rs.getString(1));
      if (written.isEmpty()) return null;
      if (readers(c, tenantId, written).stream().anyMatch(s -> s.stream().anyMatch(x -> before.contains(x) && !after.contains(x)))) {
        throw refused(connector, "a table it writes");
      }
      return null;
    });
  }

  private static List<List<String>> readers(Connection c, long tenantId, List<String> tables) {
    return Jdbc.query(c, "NewWriterScopes.readers", READERS,
        ps -> {
          ps.setArray(1, c.createArrayOf("text", tables.toArray()));
          ps.setLong(2, tenantId);
        },
        rs -> EntityRegistry.scopes(rs.getArray(1)));
  }

  private static ConfigValidationException refused(String connector, String what) {
    return new ConfigValidationException(what + " is read by an entity whose scopes connector '" + connector
        + "' would not all carry: its rows would reach readers it does not serve. Keep every scope of the entities "
        + "reading its tables, or re-apply them with scopes every writer carries.");
  }
}

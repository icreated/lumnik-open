package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The dynamic {@code t_*} tables a connector landed for the caller's tenant, and their rows (#1115)
 * — what an integrator reads between "the run is green" and "an Entity fuses it".
 *
 * <p>Both reads run on the RLS-enforced {@code default} datasource, inside the endpoint's
 * transaction, so {@code TenantConnectionInterceptor}'s {@code SET LOCAL app.current_tenant}
 * isolates the tenant in the database itself: a dynamic table and the registry both carry a
 * tenant policy. The {@code tenant_id} predicates stay as a second wall, and the registry row is
 * looked up for the caller's tenant only — a mapping registered for another tenant is a 404.
 *
 * <p>Scope bounds them too ({@link DynamicTableScope}): a table a caller may not read is left
 * out of the list, and reading its rows is the uniform {@code 403 scope not granted}.
 */
@Path("/api/platform/data/tables")
@Produces(MediaType.APPLICATION_JSON)
public class DataTablesResource {

  private static final Logger LOG = Logger.getLogger(DataTablesResource.class);

  /** The default (RLS-enforced) datasource: the tenant isolation is the database's, not this class's. */
  @Inject
  AgroalDataSource ds;

  /** A count longer than this is reported unknown rather than holding the whole list. */
  private static final int COUNT_TIMEOUT_SECONDS = 2;

  /** The whole list's counting budget: past it, the remaining tables are reported unknown. */
  private static final long COUNT_BUDGET_MILLIS = 5_000;

  /** rowCount is the caller's rows, null when the table could not be counted (gone, too slow, or past the list's budget). */
  public record TableInfo(String mappingId, String tableSchema, String tableName, Long rowCount) {}

  /**
   * Every table registered for the caller, with the caller's row count. It answered {@code -1} for
   * every table and turned any failure into an empty list — "nothing landed" — the opposite of an
   * honest answer; a failure is now a 500.
   */
  @GET
  @Transactional
  public List<TableInfo> list() throws SQLException {
    long t = RequireTenant.current();
    List<TableInfo> out = new ArrayList<>();
    try (Connection c = ds.getConnection()) {
      // One entry per mapping — each connector writing it registers it once. The table holds every
      // writer's rows, so a mapping is listed only when the caller may read every writer of its table.
      Map<String, String[]> mappings = new LinkedHashMap<>();
      Set<String> refused = new HashSet<>();
      try (PreparedStatement ps = c.prepareStatement(DynamicTableScope.REGISTERED + " ORDER BY dt.table_name, dt.mapping_id")) {
        ps.setLong(1, t);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            if (!ScopeGrants.allowsAny(EntityRegistry.scopes(rs.getArray(4)))) {
              refused.add(rs.getString(2) + "." + rs.getString(3));
            }
            mappings.putIfAbsent(rs.getString(1), new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
          }
        }
      }
      mappings.values().removeIf(v -> refused.contains(v[1] + "." + v[2]));
      // Each count is bounded, and so is their sum: many slow tables must not hold the door for
      // COUNT_TIMEOUT_SECONDS apiece. Past the budget, a table is reported unknown, not counted.
      long deadline = System.currentTimeMillis() + COUNT_BUDGET_MILLIS;
      for (String[] v : mappings.values()) {
        Long n = System.currentTimeMillis() < deadline ? count(c, v[1], v[2], t) : null;
        out.add(new TableInfo(v[0], v[1], v[2], n));
      }
    }
    return out;
  }

  /**
   * The caller's rows in one table; null — "unknown" — when the registry names a table that is gone,
   * or the count outruns {@link #COUNT_TIMEOUT_SECONDS} (an exact count scans the tenant's rows, and
   * one huge table must not hold the whole list).
   */
  private static Long count(Connection c, String schema, String table, long tenant) {
    try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM "
        + quoteIdent(schema) + "." + quoteIdent(table) + " WHERE tenant_id = ?")) {
      ps.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
      ps.setLong(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      LOG.warnf("data tables: could not count %s.%s: %s", schema, table, e.getMessage());
      return null;
    }
  }

  /**
   * One page of the caller's rows in the table registered under {@code mappingId}, in landing order
   * ({@code id}). {@code tenant_id} is left out: it is the caller's own, on every row.
   */
  @GET
  @Path("/{mappingId}/rows")
  @Transactional
  public Response rows(@PathParam("mappingId") String mappingId,
                       @QueryParam("limit") @DefaultValue("100") int limit,
                       @QueryParam("offset") @DefaultValue("0") int offset) throws SQLException {
    long t = RequireTenant.current();
    try (Connection c = ds.getConnection()) {
      Optional<String> table = DynamicTableScope.readableTable(c, mappingId, t);
      if (table.isEmpty()) return ApiErrors.notFound("no dynamic table under mapping " + mappingId);
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT * FROM " + table.get() + " WHERE tenant_id = ? ORDER BY id LIMIT ? OFFSET ?")) {
        ps.setLong(1, t);
        ps.setInt(2, Math.min(Math.max(limit, 1), 1000));
        ps.setInt(3, Math.max(offset, 0));
        try (ResultSet rs = ps.executeQuery()) {
          ResultSetMetaData meta = rs.getMetaData();
          List<Map<String, Object>> out = new ArrayList<>();
          while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
              String col = meta.getColumnLabel(i);
              if ("tenant_id".equals(col)) continue;
              row.put(col, TemporalText.render(rs.getObject(i)));
            }
            out.add(row);
          }
          return Response.ok(out).build();
        }
      }
    }
  }
}

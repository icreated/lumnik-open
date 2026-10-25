package io.lumnik.hub.api.data;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.entity.EntityRegistry;
import jakarta.ws.rs.WebApplicationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The scope of a dynamic table's rows, for every door that reads one table's rows (#1201,
 * #1204): a table holds the rows of every connector writing it, so a caller bound to
 * {@code scope:<tag>} roles reads it only when each of those connectors carries one of its tags
 * ({@code allowsAny}, as an entity read). A table one scopeless or gone writer registers is
 * readable by an unbound caller only. The list door filters its registrations by the same predicate.
 */
final class DynamicTableScope {

  private DynamicTableScope() {}

  /** The tenant's registrations: one row per connector writing a table, with that writer's tags. */
  static final String REGISTERED =
      "SELECT dt.mapping_id, dt.table_schema, dt.table_name, c.tags FROM connector.dynamic_table dt "
          + "LEFT JOIN connector.connector c ON c.id = dt.connector_id AND c.tenant_id = dt.tenant_id "
          + "WHERE dt.tenant_id = ?";

  /**
   * The quoted {@code schema.table} registered for this tenant under {@code mappingId} — the
   * registry, never the request, names it — or empty when none is. Every connector writing that
   * table registers it, under this mapping or another, and each one's scope must be granted.
   *
   * <p>It refuses by throwing, and hands back a table rather than a verdict: a door cannot call it,
   * leave the answer unread and go on to read the rows (ScopeBoundaryInventoryTest counts the call
   * as the decision).
   *
   * @throws WebApplicationException 403 {@link ScopeGrants#REFUSAL}, when a writer's scope is not granted
   */
  static Optional<String> readableTable(Connection c, String mappingId, long tenant) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(REGISTERED + " AND EXISTS (SELECT 1 FROM connector.dynamic_table m "
        + "WHERE m.tenant_id = dt.tenant_id AND m.mapping_id = ? "
        + "AND m.table_schema = dt.table_schema AND m.table_name = dt.table_name)")) {
      ps.setLong(1, tenant);
      ps.setString(2, mappingId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        String fqn = quoteIdent(rs.getString(2)) + "." + quoteIdent(rs.getString(3));
        do {
          if (!ScopeGrants.allowsAny(EntityRegistry.scopes(rs.getArray(4)))) {
            throw new WebApplicationException(ApiErrors.scopeNotGranted());
          }
        } while (rs.next());
        return Optional.of(fqn);
      }
    }
  }
}

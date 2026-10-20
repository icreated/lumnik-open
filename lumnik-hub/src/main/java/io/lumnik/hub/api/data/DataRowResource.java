package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.CitationLocator;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Drill-down from a citation hash to the source row in the dynamic table.
 *
 * <p>Lookup: {@link CitationLocator} (row_hash → mapping_id, the chunk store's seam) →
 * dynamic_table.table_schema+table_name → row. Bounded by métier scope as the table's rows are
 * ({@link DynamicTableScope}): a hash read from a citation, a log or a colleague does not widen it.
 */
@Path("/api/platform/data/row/{hash}")
@Produces(MediaType.APPLICATION_JSON)
public class DataRowResource {

  private static final Logger LOG = Logger.getLogger(DataRowResource.class);

  /** The citation store's side of the seam (closed edition); absent → no hash resolves. */
  @Inject Instance<CitationLocator> citations;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @GET
  @Transactional
  public Response get(@PathParam("hash") String rowHash) {
    long tenant = RequireTenant.current();
    // 1. Lookup mapping_id from the chunk store — through the seam, never the rag schema
    // directly (Act 2 dry-run: a native-SQL string on rag.chunk 500s in the open edition).
    // No store on the classpath = no citations exist = every hash is honestly unknown.
    if (citations.isUnsatisfied()) return Response.status(404).build();
    Optional<String> mapping = citations.get().mappingIdForRowHash(rowHash);
    if (mapping.isEmpty()) {
      return Response.status(404).build();
    }
    String mappingId = mapping.get();

    // 2. The registered table, and whether its rows' métier scope is granted (#1201).
    // 3. SELECT * from it, on the same connection. Schema/table come from the system's own
    // connector.dynamic_table registry, quoted the way the writer created them.
    //
    // tenant_id IN THE QUERY, and it is load-bearing here: this runs on the admin datasource
    // (BYPASSRLS), and a dynamic table holds many tenants' rows isolated only by RLS on the
    // default datasource — which does not apply to admin. A _row_hash is a hash of the row's
    // content, so two tenants with identical data share one, and without this predicate the
    // read could return another tenant's row. DynamicTableWriter stamps tenant_id on every
    // dynamic table, so the column is always there.
    try (Connection c = adminDs.getConnection()) {
      Optional<String> table = DynamicTableScope.readableTable(c, mappingId, tenant);
      if (table.isEmpty()) return Response.status(404).build();
      try (PreparedStatement ps = c.prepareStatement(
               "SELECT * FROM " + table.get() + " WHERE _row_hash = ? AND tenant_id = ?")) {
        ps.setString(1, rowHash);
        ps.setLong(2, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) return Response.status(404).build();
          var meta = rs.getMetaData();
          Map<String, Object> out = new LinkedHashMap<>();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            out.put(meta.getColumnLabel(i), TemporalText.render(rs.getObject(i)));
          }
          return Response.ok(out).build();
        }
      }
    } catch (WebApplicationException refusal) {
      throw refusal;  // the scope refusal, thrown through here: the caller's 403, not a 500
    } catch (Exception e) {
      // The driver's message quotes the table, the column and the value it failed on: neither the
      // caller nor the log gets it, both get what failed (#1205 review).
      LOG.errorf("data row lookup failed for mapping %s: %s", mappingId, failureSummary(e));
      return Response.status(500).entity(ApiErrors.error("could not read the cited row")).build();
    }
  }

  /** What failed, by kind and SQLSTATE: a driver's message quotes the values it failed on. */
  static String failureSummary(Exception e) {
    return switch (e) {
      case SQLException sql -> sql.getClass().getSimpleName() + " (SQLSTATE " + sql.getSQLState() + ")";
      default -> e.getClass().getSimpleName();
    };
  }
}

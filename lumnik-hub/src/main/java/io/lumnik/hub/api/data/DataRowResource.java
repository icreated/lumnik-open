package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.CitationLocator;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Drill-down from a citation hash to the source row in the dynamic table.
 *
 * <p>Lookup: {@link CitationLocator} (row_hash → mapping_id, the chunk store's seam) →
 * dynamic_table.table_schema+table_name → row.
 */
@Path("/api/platform/data/row/{hash}")
@Produces(MediaType.APPLICATION_JSON)
public class DataRowResource {

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

    // 2. Find table location from connector.dynamic_table via admin DS
    String tableSchema, tableName;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT table_schema, table_name FROM connector.dynamic_table " +
             "WHERE mapping_id = ? AND tenant_id = ? LIMIT 1")) {
      ps.setString(1, mappingId);
      ps.setLong(2, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Response.status(404).build();
        tableSchema = rs.getString(1);
        tableName = rs.getString(2);
      }
    } catch (Exception e) {
      return Response.status(500).entity(Map.of("error", e.getMessage())).build();
    }

    // 3. SELECT * from the dynamic table. Schema/table come from the system's own
    // connector.dynamic_table registry; quoting them the way the writer created them
    // keeps this defense-in-depth.
    String fqn = quoteIdent(tableSchema) + "." + quoteIdent(tableName);
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM " + fqn + " WHERE _row_hash = ?")) {
      ps.setString(1, rowHash);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Response.status(404).build();
        var meta = rs.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          Object v = rs.getObject(i);
          out.put(meta.getColumnLabel(i), v == null ? null : v.toString());
        }
        return Response.ok(out).build();
      }
    } catch (Exception e) {
      return Response.status(500).entity(Map.of("error", e.getMessage())).build();
    }
  }
}

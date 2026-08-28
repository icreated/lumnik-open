package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.agroal.DataSource;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Path("/api/platform/data/tables")
@Produces(MediaType.APPLICATION_JSON)
public class DataTablesResource {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  public record TableInfo(String mappingId, String tableSchema, String tableName, long rowCount) {}

  @GET
  @Transactional
  public List<TableInfo> list() {
    long t = RequireTenant.current();
    List<TableInfo> out = new ArrayList<>();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT mapping_id, table_schema, table_name FROM connector.dynamic_table WHERE tenant_id = ?")) {
      ps.setLong(1, t);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new TableInfo(rs.getString(1), rs.getString(2), rs.getString(3), -1L));
        }
      }
    } catch (Exception e) {
      // best-effort, empty list on failure
    }
    return out;
  }
}

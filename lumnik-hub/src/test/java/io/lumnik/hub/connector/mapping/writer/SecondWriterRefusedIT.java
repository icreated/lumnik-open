package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.testsupport.SharedTableFixture;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1199: a hub table is named after its source, so two connectors of one tenant used to land in one
 * table without anyone deciding it. The second connector's first write is now refused, naming the
 * connector that holds the table. A table another tenant writes is not shared (#1198), and a
 * sharing registered before the refusal existed keeps working.
 */
@QuarkusTest
class SecondWriterRefusedIT {

  @Inject DynamicTableWriter writer;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private final String table = "t_second1199_" + System.nanoTime();
  private final String north = "erp-north-" + table;

  @Test
  void a_second_connector_of_the_tenant_is_refused_naming_the_holder() throws Exception {
    write(1, connector(1, north));
    UUID south = connector(1, "erp-south-" + table);

    assertThatThrownBy(() -> write(1, south))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("connector." + table)
        .hasMessageContaining("'" + north + "'")
        .hasMessageContaining("source.tables[].target");
    assertThat(registrations()).isEqualTo(1);
  }

  @Test
  void another_tenants_connector_writes_the_same_table() throws Exception {
    write(1, connector(1, north));

    assertThatCode(() -> write(2, connector(2, north))).doesNotThrowAnyException();
  }

  @Test
  void a_sharing_registered_before_the_refusal_keeps_writing() throws Exception {
    write(1, connector(1, north));
    UUID south = connector(1, "erp-south-" + table);
    exec(SharedTableFixture.registerAsWriter(south, table));

    assertThatCode(() -> write(1, south)).doesNotThrowAnyException();
  }

  private UUID connector(long tenant, String name) throws Exception {
    UUID id = UUID.randomUUID();
    exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled) VALUES ('" + id + "', "
        + tenant + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, true)");
    return id;
  }

  private void write(long tenant, UUID connector) {
    MappingTarget.Table spec = new MappingTarget.Table("connector", table, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = new HashMap<>();
    sys.put("_row_hash", "h-" + connector);
    sys.put("_mapping", "jdbc:" + table.substring(2));
    sys.put("_source", "jdbc:" + table.substring(2));
    sys.put("_mapping_version", 1);
    TenantContext.runAsSystem(() -> writer.write(spec, List.of(Map.of("code", "C-1")), List.of(sys), tenant, connector));
  }

  private int registrations() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*) FROM connector.dynamic_table WHERE table_name = '" + table + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private void exec(String sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }
}

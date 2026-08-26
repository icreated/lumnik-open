package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.PostgresDialect;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresDialectIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("source")
      .withUsername("src_user")
      .withPassword("src_pwd")
      .withCopyFileToContainer(
          MountableFile.forClasspathResource("jdbc/legacy-erp-schema.sql"),
          "/docker-entrypoint-initdb.d/init.sql");

  private static Connection conn;
  private final PostgresDialect d = new PostgresDialect();

  @BeforeAll
  static void openConn() throws Exception {
    conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  @AfterAll
  static void closeConn() throws Exception { if (conn != null) conn.close(); }

  @Test
  void list_tables_returns_business_and_system_tables() throws Exception {
    List<String> tables = d.listTables(conn, "public");
    assertTrue(tables.contains("c_bpartner"));
    assertTrue(tables.contains("m_product"));
    assertTrue(tables.contains("c_order"));
    assertTrue(tables.contains("t_temp"));
    assertTrue(tables.contains("ad_meta"));
  }

  @Test
  void list_columns_returns_descriptors_for_c_bpartner() throws Exception {
    List<ColumnDescriptor> cols = d.listColumns(conn, "public", "c_bpartner");
    assertEquals("c_bpartner_id", cols.get(0).name());
    assertFalse(cols.get(0).nullable());
    assertTrue(cols.stream().anyMatch(c -> c.name().equals("name")));
    assertTrue(cols.stream().anyMatch(c -> c.name().equals("updated")));
  }

  @Test
  void list_primary_key_returns_c_bpartner_id() throws Exception {
    assertEquals(List.of("c_bpartner_id"), d.listPrimaryKey(conn, "public", "c_bpartner"));
  }

  @Test
  void list_foreign_keys_returns_c_bp_group_id_fk() throws Exception {
    List<FkDescriptor> fks = d.listForeignKeys(conn, "public", "c_bpartner");
    assertEquals(1, fks.size());
    assertEquals("c_bp_group_id", fks.get(0).column());
    assertEquals("c_bp_group", fks.get(0).targetTable());
  }

  @Test
  void estimate_row_count_returns_value() throws Exception {
    // Force statistics update so reltuples is populated.
    try (var stmt = conn.createStatement()) { stmt.execute("ANALYZE c_bpartner"); }
    assertTrue(d.estimateRowCount(conn, "public", "c_bpartner") >= 0);
  }
}

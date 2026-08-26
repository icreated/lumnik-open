package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialect;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.hub.connector.jdbc.dialect.PostgresDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 IT — no @QuarkusTest, all dependencies instantiated directly.
 * JdbcDialectRegistry.byId is a mutable HashMap; we populate it via reflection
 * to avoid CDI lifecycle requirements.
 */
@Testcontainers
class SchemaIntrospectorIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("source")
      .withUsername("src_user")
      .withPassword("src_pwd")
      .withCopyFileToContainer(
          MountableFile.forClasspathResource("jdbc/legacy-erp-schema.sql"),
          "/docker-entrypoint-initdb.d/init.sql");

  private SchemaIntrospector introspector;
  private HikariPoolRegistry poolRegistry;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    // Build dialect registry manually — no CDI.
    // byId is private final HashMap; we get the existing map and put into it.
    PostgresDialect dialect = new PostgresDialect();
    JdbcDialectRegistry dialectRegistry = new JdbcDialectRegistry();
    Field byId = JdbcDialectRegistry.class.getDeclaredField("byId");
    byId.setAccessible(true);
    ((Map<String, JdbcDialect>) byId.get(dialectRegistry)).put(dialect.id(), dialect);

    poolRegistry = new HikariPoolRegistry();

    // Wire SchemaIntrospector's @Inject fields via reflection
    introspector = new SchemaIntrospector();
    Field drf = SchemaIntrospector.class.getDeclaredField("dialectRegistry");
    drf.setAccessible(true);
    drf.set(introspector, dialectRegistry);
    Field prf = SchemaIntrospector.class.getDeclaredField("poolRegistry");
    prf.setAccessible(true);
    prf.set(introspector, poolRegistry);
  }

  @AfterEach
  void teardown() {
    if (poolRegistry != null) poolRegistry.evictAll();
  }

  @Test
  void discover_skips_t_and_ad_prefixed_tables() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    List<TableDescriptor> tables = introspector.discover(cc, "public", List.of("t_*", "ad_*"), false);
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("c_bpartner")));
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("m_product")));
    assertFalse(tables.stream().anyMatch(t -> t.name().equals("t_temp")));
    assertFalse(tables.stream().anyMatch(t -> t.name().equals("ad_meta")));
  }

  @Test
  void discover_includes_columns_pk_fk_for_c_bpartner() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    TableDescriptor bp = introspector.discover(cc, "public", List.of("t_*", "ad_*"), false)
        .stream().filter(t -> t.name().equals("c_bpartner")).findFirst().orElseThrow();
    assertEquals(List.of("c_bpartner_id"), bp.primaryKey());
    assertEquals(1, bp.foreignKeys().size());
    assertEquals("c_bp_group_id", bp.foreignKeys().get(0).column());
    assertTrue(bp.columns().size() >= 5);
    assertEquals("updated", bp.suggestedWatermark().orElse(null));
  }

  @Test
  void discover_skips_c_prefix_when_in_skip_list() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    List<TableDescriptor> tables = introspector.discover(cc, "public", List.of("c_*"), false);
    assertFalse(tables.stream().anyMatch(t -> t.name().startsWith("c_")));
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("m_product")));
  }

  @Test
  void describe_single_table_returns_descriptor() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    TableDescriptor td = introspector.describe(cc, "public", "m_product");
    assertEquals("m_product", td.name());
    assertTrue(td.columns().stream().anyMatch(c -> c.name().equals("sku")));
  }
}

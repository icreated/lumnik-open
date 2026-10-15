package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import io.lumnik.jdbc.spi.ColumnDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 IT — no @QuarkusTest, all dependencies instantiated directly.
 * The dialect registry is built with {@code JdbcDialectRegistry.of(..)} to avoid CDI lifecycle requirements.
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
  private JdbcDialectRegistry dialectRegistry;

  @BeforeEach
  void setup() throws Exception {
    // Build dialect registry manually — no CDI.
    dialectRegistry = JdbcDialectRegistry.of(List.of(new PostgresDialect()));

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
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    List<TableDescriptor> tables = introspector.discover(cc, "public", List.of("t_*", "ad_*"), false);
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("c_bpartner")));
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("m_product")));
    assertFalse(tables.stream().anyMatch(t -> t.name().equals("t_temp")));
    assertFalse(tables.stream().anyMatch(t -> t.name().equals("ad_meta")));
  }

  @Test
  void discover_includes_columns_pk_fk_for_c_bpartner() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
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
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    List<TableDescriptor> tables = introspector.discover(cc, "public", List.of("c_*"), false);
    assertFalse(tables.stream().anyMatch(t -> t.name().startsWith("c_")));
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("m_product")));
  }

  @Test
  void describe_single_table_returns_descriptor() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    TableDescriptor td = introspector.describe(cc, "public", "m_product");
    assertEquals("m_product", td.name());
    assertTrue(td.columns().stream().anyMatch(c -> c.name().equals("sku")));
  }

  /** An introspector over a PostgreSQL provider customised by the test. */
  private SchemaIntrospector introspectorOver(PostgresDialect custom) throws Exception {
    JdbcDialectRegistry registry = JdbcDialectRegistry.of(List.of(custom));
    SchemaIntrospector si = new SchemaIntrospector();
    Field drf = SchemaIntrospector.class.getDeclaredField("dialectRegistry");
    drf.setAccessible(true);
    drf.set(si, registry);
    Field prf = SchemaIntrospector.class.getDeclaredField("poolRegistry");
    prf.setAccessible(true);
    prf.set(si, poolRegistry);
    return si;
  }

  /** A PostgreSQL provider whose default exclusions are its own — to prove the introspector asks the dialect. */
  private SchemaIntrospector introspectorWithSkips(List<String> dialectDefaults) throws Exception {
    return introspectorOver(new PostgresDialect() {
      @Override
      public List<String> defaultSkipPatterns() { return dialectDefaults; }
    });
  }

  /** A provider that cannot read the {@code sku} column of m_product. */
  private SchemaIntrospector introspectorRefusingSku() throws Exception {
    return introspectorOver(new PostgresDialect() {
      @Override
      public Optional<String> unsupportedReason(ColumnDescriptor column) {
        return column.name().equals("sku")
            ? Optional.of("column sku has unsupported type " + column.jdbcType()) : Optional.empty();
      }
    });
  }

  @Test
  void without_request_patterns_the_postgresql_dialect_skips_its_three_defaults() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    List<TableDescriptor> tables = introspector.discover(cc, "public", null, false);
    assertFalse(tables.stream().anyMatch(t -> t.name().equals("t_temp")), "t_* is a PostgreSQL default");
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("c_bpartner")));
  }

  @Test
  void without_request_patterns_the_default_exclusions_are_the_dialects_not_the_introspectors() throws Exception {
    SchemaIntrospector si = introspectorWithSkips(List.of("c_*"));
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    List<TableDescriptor> tables = si.discover(cc, "public", null, false);
    assertFalse(tables.stream().anyMatch(t -> t.name().startsWith("c_")), "the dialect's c_* applies");
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("t_temp")), "t_* is no longer a hard-coded default");
  }

  @Test
  void request_patterns_still_replace_the_dialects_defaults() throws Exception {
    SchemaIntrospector si = introspectorWithSkips(List.of("c_*"));
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    List<TableDescriptor> tables = si.discover(cc, "public", List.of("m_*"), false);
    assertTrue(tables.stream().anyMatch(t -> t.name().equals("c_bpartner")), "the request's patterns won");
    assertFalse(tables.stream().anyMatch(t -> t.name().startsWith("m_")));
  }

  @Test
  void discover_names_the_unsupported_columns_of_a_table_it_still_lists() throws Exception {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    TableDescriptor product = introspectorRefusingSku().discover(cc, "public", List.of("t_*", "ad_*"), false)
        .stream().filter(t -> t.name().equals("m_product")).findFirst().orElseThrow();
    assertEquals(1, product.unsupportedColumns().size());
    assertTrue(product.unsupportedColumns().get(0).startsWith("sku "), product.unsupportedColumns().toString());
  }

  @Test
  void describe_refuses_a_table_with_an_unsupported_column_naming_table_column_and_reason() throws Exception {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    ConfigValidationException e = assertThrows(
        ConfigValidationException.class,
        () -> introspectorRefusingSku().describe(cc, "public", "m_product"));
    assertTrue(e.getMessage().contains("public.m_product") && e.getMessage().contains("column sku"), e.getMessage());
  }

  @Test
  void the_postgresql_dialect_flags_nothing_so_every_discovered_table_is_clean() {
    JdbcConnectionConfig cc = JdbcConnectionConfig.parse(
        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), dialectRegistry);
    assertTrue(introspector.discover(cc, "public", List.of("t_*", "ad_*"), false).stream()
        .allMatch(t -> t.unsupportedColumns().isEmpty()));
    assertTrue(introspector.describe(cc, "public", "m_product").unsupportedColumns().isEmpty());
  }
}

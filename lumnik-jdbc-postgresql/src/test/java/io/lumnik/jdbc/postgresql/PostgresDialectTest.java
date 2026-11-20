package io.lumnik.jdbc.postgresql;

import io.lumnik.jdbc.spi.ColumnDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PostgresDialectTest {

  private final PostgresDialect d = new PostgresDialect();

  @Test
  void id_is_postgres() {
    assertEquals("postgres", d.id());
  }

  @Test
  void default_skip_patterns_are_exactly_the_three_historical_ones() {
    assertEquals(List.of("pg_*", "information_schema*", "t_*"), d.defaultSkipPatterns());
  }

  @Test
  void quote_identifier_wraps_in_double_quotes() {
    assertEquals("\"c_bpartner\"", d.quoteIdentifier("c_bpartner"));
    assertEquals("\"a.b\"", d.quoteIdentifier("a.b"));
  }

  @Test
  void quote_identifier_escapes_embedded_double_quote() {
    assertEquals("\"weird\"\"name\"", d.quoteIdentifier("weird\"name"));
  }

  @Test
  void quote_identifier_corpus_pins_the_exact_output() {
    assertEquals("\"a\"", d.quoteIdentifier("a"));
    assertEquals("\"A b\"", d.quoteIdentifier("A b"));
    assertEquals("\"select\"", d.quoteIdentifier("select"));
    assertEquals("\"we\"\"ird\"", d.quoteIdentifier("we\"ird"));
    assertEquals("\"\"\"\"", d.quoteIdentifier("\""));
  }

  @Test
  void quote_identifier_rejects_null_or_blank() {
    assertThrows(IllegalArgumentException.class, () -> d.quoteIdentifier(null));
    assertThrows(IllegalArgumentException.class, () -> d.quoteIdentifier(""));
  }

  @Test
  void infer_watermark_picks_updated_at_first() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true),
        new ColumnDescriptor("updated_at", "TIMESTAMP", 6, false),
        new ColumnDescriptor("modified_at", "TIMESTAMP", 6, true));
    assertEquals(Optional.of("updated_at"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_updated_then_modified() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("updated", "TIMESTAMP", 6, false));
    assertEquals(Optional.of("updated"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_any_timestamp() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("created_at", "TIMESTAMP", 6, false));
    assertEquals(Optional.of("created_at"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_falls_back_to_numeric_pk_when_no_timestamp() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("id", "BIGINT", 19, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true));
    assertEquals(Optional.of("id"), d.inferWatermarkColumn(cols, List.of("id")));
  }

  @Test
  void infer_watermark_returns_empty_when_pk_is_text() {
    List<ColumnDescriptor> cols = List.of(
        new ColumnDescriptor("code", "VARCHAR", 10, false),
        new ColumnDescriptor("name", "VARCHAR", 255, true));
    assertEquals(Optional.empty(), d.inferWatermarkColumn(cols, List.of("code")));
  }

  /** pgJDBC reports timestamp and timestamptz with the same code (93): only the type name tells a
   *  wall-clock time from an instant (#1093, measured with pgJDBC 42.7.8). */
  @Test
  void only_a_timestamp_without_time_zone_is_a_wall_clock() throws Exception {
    assertTrue(d.isWallClock(metadata(Types.TIMESTAMP, "timestamp"), 1));
    assertFalse(d.isWallClock(metadata(Types.TIMESTAMP, "timestamptz"), 1));
    assertFalse(d.isWallClock(metadata(Types.DATE, "date"), 1));
    assertFalse(d.isWallClock(metadata(Types.VARCHAR, "text"), 1));
  }

  static ResultSetMetaData metadata(int type, String typeName) {
    return (ResultSetMetaData) Proxy.newProxyInstance(PostgresDialectTest.class.getClassLoader(),
        new Class<?>[] {ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getColumnType" -> type;
          case "getColumnTypeName" -> typeName;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  /** #2002: the hosts a URL connects to, as pgjdbc itself reads them — the hub judges each. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "jdbc:postgresql://erp-db:5432/erp | erp-db",
      "jdbc:postgresql://a:5432,b:5433/erp?targetServerType=primary | a,b",
      "jdbc:postgresql://[::1]:5432/erp | [::1]",
      "jdbc:postgresql:erp | localhost",
      "jdbc:postgresql:// | localhost",
  })
  void connect_hosts_are_the_drivers_own_reading(String url, String hosts) {
    assertEquals(List.of(hosts.split(",")), d.connectHosts(url));
  }

  /** A socket factory of the URL's choice could connect anywhere the hosts do not say. */
  @Test
  void a_url_that_names_its_own_socket_factory_is_refused() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> d.connectHosts("jdbc:postgresql://erp-db/erp?socketFactory=x.Y&socketFactoryArg=/tmp/s"));
    assertTrue(e.getMessage().contains("socketFactory"), e.getMessage());
  }

  /**
   * #2027: a file, a class, a keystore or a ticket of the hub's, named in the URL. Refused by name, never echoing the
   * value — as is any property outside the list.
   */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "sslcert=/home/hub/.postgresql/postgresql.crt | sslcert",
      "sslkey=/home/hub/.postgresql/postgresql.pk8 | sslkey",
      "sslrootcert=/home/hub/.postgresql/root.crt | sslrootcert",
      "sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory | sslfactory",
      "sslpasswordcallback=x.Y | sslpasswordcallback",
      "authenticationPluginClassName=x.Y | authenticationPluginClassName",
      "jaasLogin=true | jaasLogin",
      "gsslib=gssapi | gsslib",
      "requireAuth=gss | requireAuth",
      "loggerFile=/home/hub/x | loggerFile",
  })
  void a_property_outside_the_list_is_refused_by_name(String query, String name) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> d.connectHosts("jdbc:postgresql://erp-db/erp?" + query));
    assertTrue(e.getMessage().contains(name), e.getMessage());
    assertFalse(e.getMessage().contains("/home/hub") || e.getMessage().contains("x.Y"), e.getMessage());
  }

  /** A name the driver does not know may be a piece of a password ({@code password=p&ss0rd}): never quoted. */
  @Test
  void a_name_the_driver_does_not_know_is_not_quoted() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> d.connectHosts("jdbc:postgresql://erp-db/erp?user=erp&password=p&ss0rd"));
    assertEquals("it sets a parameter, which the hub does not take", e.getMessage());
  }

  /** pgjdbc lets the URL win over the pool's properties: the isolation holds only while the URL cannot name them. */
  @Test
  void a_url_cannot_override_an_isolation_property() {
    d.isolationProperties().keySet().forEach(name -> assertThrows(IllegalArgumentException.class,
        () -> d.connectHosts("jdbc:postgresql://erp-db/erp?" + name + "=x"), name));
  }

  @Test
  void the_listed_properties_pass() {
    assertEquals(List.of("erp-db"), d.connectHosts("jdbc:postgresql://erp-db:5432/erp?user=erp&password=pw&sslmode=require"
        + "&ApplicationName=lumnik&currentSchema=erp&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true&ssl=false"));
  }

  /** #2027: checking the server needs a CA, and today the only one pgjdbc would read is the hub's own root.crt. */
  @ParameterizedTest
  @ValueSource(strings = {"sslmode=verify-ca", "sslmode=verify-full", "ssl=true", "ssl=", "ssl=true&sslmode=VERIFY-FULL"})
  void a_server_check_that_would_read_the_hubs_ca_is_refused(String query) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> d.connectHosts("jdbc:postgresql://erp-db/erp?" + query));
    assertTrue(e.getMessage().contains("verify the server"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"sslmode=disable", "sslmode=allow", "sslmode=prefer", "sslmode=require", "ssl=true&sslmode=require"})
  void a_mode_that_reads_no_ca_passes(String query) {
    assertEquals(List.of("erp-db"), d.connectHosts("jdbc:postgresql://erp-db/erp?" + query));
  }

  /** What pgjdbc does not read (userinfo, a single slash) or reads as no host is refused, never guessed. */
  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:postgresql://u:p@erp-db/erp", "jdbc:postgresql:/erp", "jdbc:postgresql:///erp"})
  void a_url_the_driver_reads_no_host_in_is_refused(String url) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> d.connectHosts(url));
    assertFalse(e.getMessage().contains("erp"), e.getMessage());   // the reason, never the URL
  }

  /** #2002: a connect and a login always bounded, the reads only when asked. */
  @Test
  void timeouts_are_the_drivers_properties_in_seconds() {
    assertEquals(Map.of("connectTimeout", "10", "loginTimeout", "10"),
        d.timeoutProperties(Duration.ofSeconds(10), Optional.empty()));
    assertEquals(Map.of("connectTimeout", "10", "loginTimeout", "10", "socketTimeout", "30"),
        d.timeoutProperties(Duration.ofSeconds(10), Optional.of(Duration.ofSeconds(30))));
  }

  /**
   * #2000, #2002: a {@code service} is resolved from the hub's own pg_service.conf — its host, its settings. Refused
   * even when the hub has one that would answer: the URL, not the hub's files, says where a connector connects.
   */
  @Test
  void a_service_the_hubs_own_file_would_resolve_is_refused() throws Exception {
    Path file = Files.createTempFile("pg_service", ".conf");
    Files.writeString(file, "[hub_db]\nhost=10.0.0.5\nport=5432\ndbname=hub\n");
    String before = System.getProperty("org.postgresql.pgservicefile");
    System.setProperty("org.postgresql.pgservicefile", file.toString());
    try {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> d.connectHosts("jdbc:postgresql://?service=hub_db"));
      assertTrue(e.getMessage().contains("service"), e.getMessage());
    } finally {
      if (before == null) System.clearProperty("org.postgresql.pgservicefile"); else System.setProperty("org.postgresql.pgservicefile", before);
      Files.deleteIfExists(file);
    }
  }
}

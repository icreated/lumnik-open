package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.jdbc.spi.Comparison;
import io.lumnik.jdbc.spi.FkDescriptor;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.jdbc.spi.KeyComparison;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code lm validate} / {@code lm apply} decide the dialect through the SAME door as the runtime: the
 * registry, with its own messages. A declared dialect that is absent or does not accept the URL is a
 * {@code spec.connection.dialect} error; an inference that cannot be made is a {@code spec.connection.url} one.
 */
class JdbcConnectorValidatorDialectTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Oracle-shaped provider that only knows its name and URL prefix. */
  private static final class StubOracle implements JdbcDialect {
    public String id() { return "oracle"; }
    public boolean acceptsUrl(String url) { return url != null && url.startsWith("jdbc:oracle:"); }
    public String driverClass() { throw new UnsupportedOperationException(); }
    public String quoteIdentifier(String i) { throw new UnsupportedOperationException(); }
    public KeyComparison keyComparison(List<String> c, Comparison k) { throw new UnsupportedOperationException(); }
    public String limitClause(int l) { throw new UnsupportedOperationException(); }
    public List<String> defaultSkipPatterns() { throw new UnsupportedOperationException(); }
    public List<String> listTables(Connection c, String s) { throw new UnsupportedOperationException(); }
    public boolean tableExists(Connection c, String s, String t) { throw new UnsupportedOperationException(); }
    public List<ColumnDescriptor> listColumns(Connection c, String s, String t) { throw new UnsupportedOperationException(); }
    public List<FkDescriptor> listForeignKeys(Connection c, String s, String t) { throw new UnsupportedOperationException(); }
    public List<String> listPrimaryKey(Connection c, String s, String t) { throw new UnsupportedOperationException(); }
    public long estimateRowCount(Connection c, String s, String t) { throw new UnsupportedOperationException(); }
    public Optional<String> inferWatermarkColumn(List<ColumnDescriptor> c, List<String> p) { throw new UnsupportedOperationException(); }
  }

  private final JdbcDialectRegistry registry =
      JdbcDialectRegistry.of(List.of(new PostgresDialect(), new StubOracle()));

  private List<ValidationError> connectionErrors(String url, String dialect) throws Exception {
    JdbcConnectorValidator v = new JdbcConnectorValidator();
    v.dialectRegistry = registry;
    String d = dialect == null ? "" : ", \"dialect\": \"" + dialect + "\"";
    JsonNode spec = MAPPER.readTree("{\"connection\": {\"url\": \"" + url + "\"" + d + "}}");
    return v.validateCrossField(spec).stream().filter(e -> e.path().startsWith("spec.connection")).toList();
  }

  /** What the registry itself says — the validator must surface exactly this, never retype it. */
  private String registryMessage(String url, String dialect) {
    try {
      registry.resolveFor(url, dialect);
      return null;
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  @Test
  void an_installed_dialect_with_its_url_is_accepted() throws Exception {
    assertThat(connectionErrors("jdbc:oracle:thin:@//h:1521/ERP", "oracle")).isEmpty();
    assertThat(connectionErrors("jdbc:oracle:thin:@//h:1521/ERP", null)).isEmpty();
    assertThat(connectionErrors("jdbc:postgresql://h/d", "postgres")).isEmpty();
    assertThat(connectionErrors("jdbc:postgresql://h/d", "postgresql")).isEmpty();
    assertThat(connectionErrors("jdbc:postgresql://h/d", null)).isEmpty();
  }

  @Test
  void a_declared_dialect_that_does_not_accept_the_url_is_a_dialect_error_with_the_registrys_message() throws Exception {
    List<ValidationError> errs = connectionErrors("jdbc:postgresql://h/d", "oracle");
    assertThat(errs).hasSize(1);
    assertThat(errs.get(0).path()).isEqualTo("spec.connection.dialect");
    assertThat(errs.get(0).message()).isEqualTo(registryMessage("jdbc:postgresql://h/d", "oracle"));
  }

  @Test
  void a_declared_dialect_that_is_not_installed_is_a_dialect_error_listing_the_installed_ones() throws Exception {
    List<ValidationError> errs = connectionErrors("jdbc:db2://h/d", "db2");
    assertThat(errs).hasSize(1);
    assertThat(errs.get(0).path()).isEqualTo("spec.connection.dialect");
    assertThat(errs.get(0).message()).isEqualTo(registryMessage("jdbc:db2://h/d", "db2"))
        .contains("not installed").contains("oracle, postgres");
  }

  @Test
  void an_url_no_installed_dialect_accepts_is_a_url_error_with_the_registrys_message() throws Exception {
    List<ValidationError> errs = connectionErrors("jdbc:mariadb://h/d", null);
    assertThat(errs).hasSize(1);
    assertThat(errs.get(0).path()).isEqualTo("spec.connection.url");
    assertThat(errs.get(0).message()).isEqualTo(registryMessage("jdbc:mariadb://h/d", null));
  }
}

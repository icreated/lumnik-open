package io.lumnik.hub.connector.jdbc.dialect;

import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.jdbc.spi.Comparison;
import io.lumnik.jdbc.spi.FkDescriptor;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.jdbc.spi.KeyComparison;
import io.quarkus.runtime.Startup;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDialectRegistryTest {

  /** A dialect that only knows its names and the URL prefix it accepts. */
  private record Stub(String id, Set<String> aliases, String prefix) implements JdbcDialect {
    Stub(String id, String prefix) { this(id, Set.of(), prefix); }
    public boolean acceptsUrl(String url) { return url != null && url.startsWith(prefix); }
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

  private static final Stub PG = new Stub("postgres", Set.of("postgresql"), "jdbc:postgresql:");
  private static final Stub ORA = new Stub("oracle", "jdbc:oracle:");

  private static JdbcDialectRegistry registry(JdbcDialect... d) { return JdbcDialectRegistry.of(List.of(d)); }

  // ── startup: names are claimed once ────────────────────────────────────────

  @Test
  void two_providers_with_the_same_id_fail_startup_naming_both() {
    assertThatThrownBy(() -> registry(PG, new Stub("postgres", "jdbc:x:")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'postgres'").hasMessageContaining("claimed by both");
  }

  @Test
  void two_providers_with_the_same_alias_fail_startup() {
    assertThatThrownBy(() -> registry(PG, new Stub("pg2", Set.of("postgresql"), "jdbc:x:")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("'postgresql'");
  }

  @Test
  void an_alias_colliding_with_another_providers_id_fails_startup() {
    assertThatThrownBy(() -> registry(ORA, new Stub("other", Set.of("oracle"), "jdbc:x:")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("'oracle'");
  }

  @Test
  void names_are_compared_after_trim_and_lowercase() {
    assertThatThrownBy(() -> registry(PG, new Stub("  POSTGRES ", "jdbc:x:")))
        .isInstanceOf(IllegalStateException.class);
  }

  // ── explicit dialect ───────────────────────────────────────────────────────

  @Test
  void an_explicit_dialect_resolves_by_id_or_alias_whatever_its_case() {
    JdbcDialectRegistry r = registry(PG, ORA);
    assertThat(r.resolve("postgres")).isSameAs(PG);
    assertThat(r.resolve("  PostgreSQL ")).isSameAs(PG);
    assertThat(r.resolveFor("jdbc:postgresql://h/d", "POSTGRES")).isSameAs(PG);
  }

  @Test
  void an_explicit_dialect_that_is_not_installed_is_refused_with_the_sorted_available_list() {
    assertThatThrownBy(() -> registry(PG, ORA).resolveFor("jdbc:db2://h/d", "db2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("JDBC dialect 'db2' is not installed. Available: oracle, postgres");
  }

  @Test
  void an_explicit_dialect_whose_url_it_does_not_accept_is_refused() {
    assertThatThrownBy(() -> registry(PG, ORA).resolveFor("jdbc:oracle:thin:@//h/x", "postgres"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'postgres'").hasMessageContaining("jdbc:oracle");
  }

  // ── inference ──────────────────────────────────────────────────────────────

  @Test
  void without_a_dialect_exactly_one_accepting_provider_is_inferred() {
    JdbcDialectRegistry r = registry(PG, ORA);
    assertThat(r.resolveFor("jdbc:oracle:thin:@//h/x", null)).isSameAs(ORA);
    assertThat(r.resolveFor("jdbc:postgresql://h/d", "   ")).isSameAs(PG);
  }

  @Test
  void without_a_dialect_and_no_accepting_provider_the_installed_ones_are_listed() {
    assertThatThrownBy(() -> registry(ORA, PG).resolveFor("jdbc:mariadb://h/d", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url scheme 'jdbc:mariadb'").hasMessageContaining("Available: oracle, postgres")
        .hasMessageNotContaining("//h/d");
  }

  @Test
  void without_a_dialect_and_several_accepting_providers_it_is_ambiguous_never_first_wins() {
    JdbcDialectRegistry r = registry(new Stub("b-pg", "jdbc:postgresql:"), new Stub("a-pg", "jdbc:postgresql:"));
    assertThatThrownBy(() -> r.resolveFor("jdbc:postgresql://h/d", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("claimed by several dialects: a-pg, b-pg")
        .hasMessageNotContaining("//h/d");
  }

  @Test
  void the_registry_is_built_at_boot_so_a_duplicate_name_stops_the_hub_instead_of_the_first_request() {
    // An @ApplicationScoped bean is lazy: without @Startup the duplicate-name refusal would fire on
    // the first JDBC request, as a 500, while the hub looked healthy.
    assertThat(JdbcDialectRegistry.class.isAnnotationPresent(Startup.class)).isTrue();
  }

  @Test
  void installed_ids_are_sorted() {
    assertThat(registry(PG, ORA).installedIds()).containsExactly("oracle", "postgres");
  }
}

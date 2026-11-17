package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2050: what was applied before owners existed. On a hub with one customer tenant, that tenant wrote it and keeps
 * changing it: V99 makes it its own. With none or several, it stays shared, as it was read; the built-in apps stay shared
 * whatever the count. Flyway ran V99 before the test boots, so each case seeds rows without an owner, stands a table of
 * its own in for {@code identity.tenant}, and replays the migration's own SQL — twice. Each case is rolled back.
 */
@QuarkusTest
class ManifestOwnerMigrationIT {

  private static final String V99 = "db/migration/connector/V99__manifest_owner.sql";

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private Connection tx;

  @BeforeEach
  void begin() throws Exception {
    tx = adminDs.getConnection();
    tx.setAutoCommit(false);
    exec("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES ('t2050mig', 'kind: Entity')",
        "INSERT INTO connector.saved_view (name, scope, title, yaml_text) VALUES ('t2050mig', 's', 't', 'kind: View')",
        "INSERT INTO connector.app_registration (name, scope, title, icon, accent_color, background_color, yaml_text) "
            + "VALUES ('t2050mig', 's', 't', 'clients', '#8b7ff0', '#14121f', 'kind: App')");
  }

  @AfterEach
  void rollback() throws Exception {
    tx.rollback();
    tx.setAutoCommit(true);
    tx.close();
  }

  @Test
  void with_no_customer_tenant_everything_stays_shared() throws Exception {
    tenants(DeploymentTenancy.HUBADMIN_TENANT);

    replayV99();
    replayV99();

    assertThat(owners()).containsOnlyNulls();
  }

  @Test
  void with_one_customer_tenant_what_it_applied_is_its_own() throws Exception {
    tenants(1, DeploymentTenancy.HUBADMIN_TENANT);

    replayV99();
    replayV99();

    assertThat(owners()).containsOnly(1L);
  }

  @Test
  void with_several_customer_tenants_everything_stays_shared() throws Exception {
    tenants(1, 2, DeploymentTenancy.HUBADMIN_TENANT);

    replayV99();

    assertThat(owners()).containsOnlyNulls();
  }

  /**
   * The built-in apps (as V48 seeded them) stay shared. A default app the tenant made becomes its own default, and a
   * built-in the shared one: every tenant still has a default to fall back on. The case sets its own apps: the shared
   * test database holds whatever other tests left of clients and stock.
   */
  @Test
  void with_one_customer_tenant_the_built_in_apps_stay_shared() throws Exception {
    tenants(1, DeploymentTenancy.HUBADMIN_TENANT);
    exec("DELETE FROM connector.app_registration WHERE name <> 't2050mig'",
        "INSERT INTO connector.app_registration (name, scope, title, icon, accent_color, background_color, yaml_text) "
            + "VALUES ('t2050migbuiltin', 's', 't', 'clients', '#8b7ff0', '#14121f', '# seeded at V48 — a test')",
        "INSERT INTO connector.app_registration (name, scope, title, icon, accent_color, background_color, yaml_text, "
            + "is_default) VALUES ('t2050migdefault', 's', 't', 'clients', '#8b7ff0', '#14121f', 'kind: App', true)");

    replayV99();
    replayV99();

    assertThat(owner("app_registration", "t2050migbuiltin")).isEmpty();
    assertThat(owner("app_registration", "t2050mig")).contains(1L);
    assertThat(defaults()).containsExactlyInAnyOrder("t2050migbuiltin=shared", "t2050migdefault=1");
  }

  private List<String> defaults() {
    return Jdbc.query(tx, "test.defaults", "SELECT name, owner_tenant_id FROM connector.app_registration WHERE is_default",
        ps -> {}, rs -> rs.getString(1) + "=" + Optional.ofNullable(rs.getObject(2, Long.class)).map(String::valueOf)
            .orElse("shared"));
  }

  /** The seeded rows' owners: the Entity's, the View's, the App's. */
  private Long[] owners() {
    return new Long[] {
        owner("entity_manifest", "t2050mig").orElse(null),
        owner("saved_view", "t2050mig").orElse(null),
        owner("app_registration", "t2050mig").orElse(null)};
  }

  private Optional<Long> owner(String table, String name) {
    return Jdbc.queryOne(tx, "test.owner", "SELECT owner_tenant_id FROM connector." + table + " WHERE name = ?",
        ps -> ps.setString(1, name), rs -> rs.getObject(1, Long.class));
  }

  /** {@code identity.tenant} as the case has it: the real one is set aside for the transaction. */
  private void tenants(long... ids) throws Exception {
    exec("ALTER TABLE identity.tenant RENAME TO tenant_t2050",
        "CREATE TABLE identity.tenant (id BIGINT PRIMARY KEY)",
        "INSERT INTO identity.tenant (id) VALUES "
            + Arrays.stream(ids).mapToObj(id -> "(" + id + ")").collect(Collectors.joining(", ")));
  }

  private void replayV99() throws Exception {
    try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(V99),
        V99 + " is not on the test classpath")) {
      exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private void exec(String... sql) throws Exception {
    try (Statement st = tx.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }
}

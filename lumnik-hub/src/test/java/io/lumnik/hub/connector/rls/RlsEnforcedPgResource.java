package io.lumnik.hub.connector.rls;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * A Postgres where RLS is ACTUALLY enforced for the application: the default datasource connects as
 * a non-owner {@code lumnik_app} role while {@code lumnik_admin} runs Flyway and owns the tables —
 * the production topology of infra/postgres/init/01-roles.sql. The shared DevServices container
 * connects as the DB owner (RLS skipped for owners); this harness closes that blind spot.
 *
 * <p>Init also pre-creates the application schemas with USAGE grants: the migrations only grant
 * schema USAGE for {@code connector} (V9) — a known latent gap for true non-owner deployments
 * (auto-memory: schema-usage-grant-gap).
 */
public class RlsEnforcedPgResource implements QuarkusTestResourceLifecycleManager {

  /** The container's superuser, for a test that must act as the DBA (signal another backend). */
  public static final String SUPERUSER = "postgres";
  public static final String SUPERUSER_PASSWORD = "postgres";

  /** The superuser bootstrap every install runs after creating the roles (initdb, selfhost up.sh, the helm Job). */
  public static final Path EXTENSIONS_SQL = Path.of("../infra/postgres/init/02-extensions.sql");

  private GenericContainer<?> pg;

  @Override
  public Map<String, String> start() {
    pg = new GenericContainer<>("pgvector/pgvector:0.8.6-pg16")
        .withEnv("POSTGRES_DB", "lumnik")
        .withEnv("POSTGRES_USER", SUPERUSER)
        .withEnv("POSTGRES_PASSWORD", SUPERUSER_PASSWORD)
        .withExposedPorts(5432)
        // NOT forListeningPort(): the Postgres entrypoint runs a TEMPORARY server for initdb and
        // then restarts it, so the port accepts connections during a window when the real server
        // is not up yet. Under load the first JDBC connect lands in that window and dies with a
        // SocketTimeoutException mid-SSL-negotiation, taking every @QuarkusTest on this profile
        // down with it. Testcontainers' own PostgreSQLContainer waits for the ready line TWICE for
        // exactly this reason; the MinIO sites in this tree already wait on /minio/health/ready.
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
            .withStartupTimeout(Duration.ofMinutes(2)));
    pg.start();
    String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/lumnik";

    try (Connection c = DriverManager.getConnection(url, SUPERUSER, SUPERUSER_PASSWORD);
         Statement st = c.createStatement()) {
      // 01-roles.sql mirror
      st.execute("CREATE ROLE lumnik_admin WITH LOGIN PASSWORD 'admin'");
      st.execute("CREATE ROLE lumnik_app WITH LOGIN PASSWORD 'app'");
      st.execute("CREATE ROLE lumnik_readonly WITH LOGIN PASSWORD 'ro'");
      st.execute("ALTER ROLE lumnik_readonly SET default_transaction_read_only = on");
      st.execute("GRANT CONNECT ON DATABASE lumnik TO lumnik_app, lumnik_readonly");
      st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin "
          + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lumnik_app");
      st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin "
          + "GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO lumnik_app");
      // The shipped file itself, not a copy: database grants, the vector extension, the app schemas with their
      // USAGE grants, and the readonly role's REVOKEs (#1987) that ReadonlyHardening checks before every Ask.
      st.execute(Files.readString(EXTENSIONS_SQL));
    } catch (SQLException | IOException e) {
      throw new RuntimeException("RLS harness init failed", e);
    }

    return Map.of(
        "quarkus.datasource.jdbc.url", url,
        "quarkus.datasource.username", "lumnik_app",
        "quarkus.datasource.password", "app",
        "quarkus.datasource.\"admin\".jdbc.url", url,
        "quarkus.datasource.\"admin\".username", "lumnik_admin",
        "quarkus.datasource.\"admin\".password", "admin",
        "quarkus.datasource.\"readonly\".jdbc.url", url,
        "quarkus.datasource.\"readonly\".username", "lumnik_readonly",
        "quarkus.datasource.\"readonly\".password", "ro");
  }

  /** One statement as the database superuser, from a test running on this harness (re-grant, signal a backend…). */
  public static void asSuperuser(String sql) throws SQLException {
    String url = ConfigProvider.getConfig().getValue("quarkus.datasource.\"admin\".jdbc.url", String.class);
    try (Connection c = DriverManager.getConnection(url, SUPERUSER, SUPERUSER_PASSWORD);
         Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  @Override
  public void stop() {
    if (pg != null) pg.stop();
  }
}

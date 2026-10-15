package io.lumnik.hub.search.contract;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * A Postgres where RLS is ACTUALLY enforced, local to this package's own contract suite (#674): the
 * default datasource connects as a non-owner {@code lumnik_app} role while {@code lumnik_admin} runs
 * Flyway and owns the tables — the same production topology
 * {@code io.lumnik.hub.connector.rls.RlsEnforcedPgResource} establishes for the ~15 RLS ITs elsewhere
 * in this tree. Deliberately NOT that shared class: this suite is the first to combine real RLS
 * enforcement with real OIDC/JWT auth over full HTTP ({@link SearchProviderContractRlsProfile}), and
 * its schema-grant list needs {@code search_pg} (the #705 module, added after that shared class's own
 * list was last touched) from day one — a change to the shared class would touch every one of its
 * other ~15 consumers for a need only this package has. Same duplication posture CLAUDE.md already
 * states for {@code io.lumnik.hub.search.mark}'s own package-private helpers: local and self-contained
 * beats reaching into shared infra.
 */
public class SearchProviderContractRlsPgResource implements QuarkusTestResourceLifecycleManager {

  public static final String SUPERUSER = "postgres";
  public static final String SUPERUSER_PASSWORD = "postgres";

  private GenericContainer<?> pg;

  @Override
  public Map<String, String> start() {
    pg = new GenericContainer<>("pgvector/pgvector:0.8.6-pg16")
        .withEnv("POSTGRES_DB", "lumnik")
        .withEnv("POSTGRES_USER", SUPERUSER)
        .withEnv("POSTGRES_PASSWORD", SUPERUSER_PASSWORD)
        .withExposedPorts(5432)
        // See RlsEnforcedPgResource's own identical comment: the Postgres entrypoint runs a
        // temporary server for initdb and restarts it, so the port accepts connections during a
        // window before the real server is up — wait for the ready log line twice.
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
            .withStartupTimeout(Duration.ofMinutes(2)));
    pg.start();
    String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/lumnik";

    try (Connection c = DriverManager.getConnection(url, SUPERUSER, SUPERUSER_PASSWORD);
         Statement st = c.createStatement()) {
      // 01-roles.sql mirror, same as RlsEnforcedPgResource.
      st.execute("CREATE ROLE lumnik_admin WITH LOGIN PASSWORD 'admin'");
      st.execute("CREATE ROLE lumnik_app WITH LOGIN PASSWORD 'app'");
      st.execute("CREATE ROLE lumnik_readonly WITH LOGIN PASSWORD 'ro'");
      st.execute("ALTER ROLE lumnik_readonly SET default_transaction_read_only = on");
      st.execute("GRANT CREATE, CONNECT, TEMP ON DATABASE lumnik TO lumnik_admin");
      st.execute("GRANT CONNECT ON DATABASE lumnik TO lumnik_app, lumnik_readonly");
      st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin "
          + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lumnik_app");
      st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE lumnik_admin "
          + "GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO lumnik_app");
      // from-scratch gaps (memory dev-hub-from-scratch-boot): superuser-only extension.
      st.execute("CREATE EXTENSION IF NOT EXISTS vector");
      // latent schema-USAGE gap (memory: schema-usage-grant-gap) — pre-create app schemas owned by
      // the migration user + grant USAGE, "search_pg" included from the start since this list is
      // written fresh for this suite (#705's module didn't exist when the shared allowlist was
      // last updated).
      for (String schema : new String[]{"core", "connector", "identity", "rag", "platform", "search_pg"}) {
        st.execute("CREATE SCHEMA IF NOT EXISTS " + schema + " AUTHORIZATION lumnik_admin");
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO lumnik_app, lumnik_readonly");
      }
    } catch (SQLException e) {
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

  @Override
  public void stop() {
    if (pg != null) pg.stop();
  }
}

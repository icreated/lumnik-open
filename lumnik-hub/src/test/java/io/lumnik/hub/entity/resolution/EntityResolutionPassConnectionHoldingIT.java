package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.AgroalPoolInterceptor;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code paginateByPk} held its SELECT connection open (a try-with-resources spanning the whole
 * pagination run) while resolving each row — and every row's resolution acquires its OWN
 * connection from the very same admin pool ({@link EntityIdentityRepository}, reached through
 * {@link CascadeResolver}). Holding the SELECT connection idle for the whole pass wastes a
 * checkout the pool could otherwise hand to the row's own resolution, and is a genuine deadlock
 * risk if the admin pool is ever sized down to one connection.
 *
 * <p>Constructs {@link EntityResolutionPass} directly (same posture as {@code
 * EntityResolutionPassConfigTest}) rather than through CDI: a {@code @Inject}-ed bean is a client
 * proxy whose own fields are never populated (only its methods delegate to the real contextual
 * instance), so swapping {@code adminDs} on it would silently do nothing. The resolver is a stub
 * that never touches the database — it only reports whether the pagination connection was still
 * open at the moment it ran — isolating this from every other concern {@link CascadeResolver}
 * carries.
 */
@QuarkusTest
class EntityResolutionPassConnectionHoldingIT {

  @Inject EntityRegistry registry;
  @Inject ResolutionCoverageStore coverage;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "respoolit620";
  private static final String TABLE = "t_res_pool_it620_erp";

  private static final String MANIFEST = """
      kind: Entity
      name: ResPoolIt620
      sources:
        - table: t_res_pool_it620_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_pool_it620_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector." + TABLE);
      st.execute("CREATE TABLE connector." + TABLE + " ("
          + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
          + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
      st.execute("DELETE FROM core.entity_identity WHERE entity_type = '" + ENTITY_TYPE + "'");
    }
    registry.put(new EntityManifestParser().parse(MANIFEST));
  }

  @Test
  void paginateByPk_releases_its_select_connection_before_resolving_a_row() throws Exception {
    long tenant = 98_501;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + TABLE + " (tenant_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, 'pool-1', 'pool1@example.com'), (?, 'pool-2', 'pool2@example.com')")) {
      ps.setLong(1, tenant);
      ps.setLong(2, tenant);
      ps.executeUpdate();
    }

    AtomicReference<Connection> selectConnection = new AtomicReference<>();
    AtomicBoolean connectionOpenDuringResolve = new AtomicBoolean(false);

    EntityResolutionPass testPass = new EntityResolutionPass();
    testPass.registry = registry;
    testPass.coverage = coverage;   // #748: runReplay snapshots and covers runs; the real store, on its own connections
    testPass.statistics = mock(CrosswalkStatistics.class);
    testPass.batchSize = 500;
    testPass.adminDs = observingDataSource(adminDs, selectConnection);
    testPass.resolver = new CascadeResolver() {
      @Override
      public ResolutionResult resolve(long tenantId, String entityType, String source, String sourceId,
          List<EntityManifest.CascadeRule> cascade, Map<String, String> rawFieldValues, UUID runId) {
        Connection c = selectConnection.get();
        try {
          if (c != null && !c.isClosed()) connectionOpenDuringResolve.set(true);
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
        return new ResolutionResult(EntityResolutionState.RESOLVED, UUID.randomUUID(), true, 0, 0, List.of());
      }
    };

    testPass.runReplay(tenant, "ResPoolIt620");

    assertThat(connectionOpenDuringResolve.get())
        .as("resolveOne must run only after the pagination SELECT connection has been released, "
            + "since resolving a row acquires its own admin connection")
        .isFalse();
  }

  /** Records whichever connection {@code paginateByPk} last acquired, so the test can ask
   *  {@code Connection.isClosed()} on it at the moment the stub resolver runs. */
  private static AgroalDataSource observingDataSource(AgroalDataSource real, AtomicReference<Connection> holder) {
    return new AgroalDataSource() {
      @Override public Connection getConnection() throws SQLException {
        Connection c = real.getConnection();
        holder.set(c);
        return c;
      }
      @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
      @Override public Connection getReadOnlyConnection() { throw new UnsupportedOperationException(); }
      @Override public AgroalDataSourceConfiguration getConfiguration() { throw new UnsupportedOperationException(); }
      @Override public AgroalDataSourceMetrics getMetrics() { throw new UnsupportedOperationException(); }
      @Override public void flush(FlushMode mode) { throw new UnsupportedOperationException(); }
      @Override public void setPoolInterceptors(Collection<? extends AgroalPoolInterceptor> interceptors) { throw new UnsupportedOperationException(); }
      @Override public List<AgroalPoolInterceptor> getPoolInterceptors() { throw new UnsupportedOperationException(); }
      @Override public void close() { /* no-op */ }
      @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
      @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
      @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
      @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
      @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
      @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
      @Override public boolean isWrapperFor(Class<?> iface) { throw new UnsupportedOperationException(); }
    };
  }
}

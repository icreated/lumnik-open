package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.AgroalPoolInterceptor;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity read is not a JPA event — no listener can see it — so {@link EntityReadResource#read}
 * carries an explicit, greppable audit call (design doc §4). This pins the three things that
 * decide whether that call is right: it fires on an actual successful read, keyed by the entity
 * name; it never fires for a refused read (that proof lives in {@link EntityScopeBindingUnitTest},
 * which already exercises every refusal path on this same resource); and a broken audit write
 * can never fail the read that already succeeded.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityReadAuditTest {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject EntityManager em;
  @Inject EntityRegistry registry;
  @Inject UserTransaction utx;

  static final String MANIFEST = """
      kind: Entity
      name: AuditProbeEntity
      match: [code]
      sources:
        - table: t_audit_probe
          map:
            code: code
            label: label
      """;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  private Long tenantId;

  @BeforeEach
  void seedAndApply() throws Exception {
    tenantId = 9100L + System.nanoTime() % 1000;
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_auditprobeentity\"");
      st.execute("DROP TABLE IF EXISTS connector.t_audit_probe");
      st.execute("CREATE TABLE connector.t_audit_probe "
          + "(tenant_id bigint NOT NULL, \"code\" text, \"label\" text)");
      st.execute("ALTER TABLE connector.t_audit_probe ENABLE ROW LEVEL SECURITY");
      st.execute("CREATE POLICY t_audit_probe_iso ON connector.t_audit_probe "
          + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
      st.execute("GRANT SELECT ON connector.t_audit_probe TO lumnik_app");
      st.execute("INSERT INTO connector.t_audit_probe VALUES (" + tenantId + ",'P-1','Probe One')");
    }
    given().config(YAML_CFG).header("X-Tenant-ID", String.valueOf(tenantId)).header("X-User-ID", "1")
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @AfterEach
  void clearThreadLocals() {
    TenantContextFilter.CURRENT_TENANT.remove();
    TenantContextFilter.CURRENT_USER.remove();
  }

  @Test
  void a_successful_read_writes_one_read_audit_row_keyed_by_the_entity_name() throws Exception {
    long before = countReadRows("AuditProbeEntity");

    given().header("X-Tenant-ID", String.valueOf(tenantId)).header("X-User-ID", "555")
        .get("/api/entities/auditprobeentity")
        .then().statusCode(200)
        .body("size()", Matchers.equalTo(1));

    long after = countReadRows("AuditProbeEntity");
    assertThat(after).isEqualTo(before + 1);

    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT entity_type, entity_key, operation, tenant_id, actor_id FROM core.audit_outbox "
           + "WHERE entity_type = ? AND entity_key = ? AND operation = 'READ' "
           + "ORDER BY id DESC LIMIT 1")) {
      ps.setString(1, "Entity");
      ps.setString(2, "AuditProbeEntity");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("expected a READ audit row for AuditProbeEntity").isTrue();
        assertThat(rs.getString("entity_type")).isEqualTo("Entity");
        assertThat(rs.getString("entity_key")).isEqualTo("AuditProbeEntity");
        assertThat(rs.getString("operation")).isEqualTo("READ");
        assertThat(rs.getLong("tenant_id")).isEqualTo(tenantId);
        assertThat(rs.getLong("actor_id")).isEqualTo(555L);
      }
    }
  }

  /**
   * THE FAIL-SOFT PIN. Same rule as {@code AuditEntityListener}, and Task 2 proved how subtly it
   * can be violated: an audit write must never turn a working read into an error. This calls
   * {@link EntityReadResource#read} directly (bypassing the interceptor-driven HTTP path) on a
   * resource wired with the REAL {@code em}/{@code registry} but a POISONED admin datasource whose
   * {@code getConnection()} always throws — proving the read still returns its data.
   */
  @Test
  void a_broken_audit_write_does_not_fail_the_read() throws Exception {
    EntityReadResource res = new EntityReadResource();
    res.registry = registry;
    res.em = em;
    res.adminDs = poisonedDataSource();

    Response r = inTx(() -> {
      TenantContextFilter.CURRENT_TENANT.set(tenantId);
      TenantContextFilter.CURRENT_USER.set(555L);
      return res.read("auditprobeentity", emptyUriInfo(), 100, 0);
    });

    assertThat(r.getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) r.getEntity();
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("code")).isEqualTo("P-1");
  }

  private long countReadRows(String entityKey) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT count(*) FROM core.audit_outbox WHERE entity_type = 'Entity' "
           + "AND entity_key = ? AND operation = 'READ'")) {
      ps.setString(1, entityKey);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private <T> T inTx(Callable<T> work) throws Exception {
    utx.begin();
    try {
      em.createNativeQuery("SET LOCAL app.current_tenant = " + tenantId).executeUpdate();
      T result = work.call();
      utx.commit();
      return result;
    } catch (Exception e) {
      try { utx.rollback(); } catch (Exception ignored) { /* best-effort */ }
      throw e;
    }
  }

  /** {@code getConnection()} always throws — every other member is unused by the code under test. */
  private static AgroalDataSource poisonedDataSource() {
    return new AgroalDataSource() {
      @Override public Connection getConnection() throws SQLException {
        throw new SQLException("boom: audit connection deliberately broken for the test");
      }
      @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("boom");
      }
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

  /** Minimal {@link UriInfo} stub — the read under test carries no query-string filters. */
  private static UriInfo emptyUriInfo() {
    return new UriInfo() {
      @Override public String getPath() { throw new UnsupportedOperationException(); }
      @Override public String getPath(boolean decode) { throw new UnsupportedOperationException(); }
      @Override public List<PathSegment> getPathSegments() { throw new UnsupportedOperationException(); }
      @Override public List<PathSegment> getPathSegments(boolean decode) { throw new UnsupportedOperationException(); }
      @Override public URI getRequestUri() { throw new UnsupportedOperationException(); }
      @Override public UriBuilder getRequestUriBuilder() { throw new UnsupportedOperationException(); }
      @Override public URI getAbsolutePath() { throw new UnsupportedOperationException(); }
      @Override public UriBuilder getAbsolutePathBuilder() { throw new UnsupportedOperationException(); }
      @Override public URI getBaseUri() { throw new UnsupportedOperationException(); }
      @Override public UriBuilder getBaseUriBuilder() { throw new UnsupportedOperationException(); }
      @Override public MultivaluedMap<String, String> getPathParameters() { throw new UnsupportedOperationException(); }
      @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) { throw new UnsupportedOperationException(); }
      @Override public MultivaluedMap<String, String> getQueryParameters() { return new MultivaluedHashMap<>(); }
      @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) { return new MultivaluedHashMap<>(); }
      @Override public List<String> getMatchedURIs() { throw new UnsupportedOperationException(); }
      @Override public List<String> getMatchedURIs(boolean decode) { throw new UnsupportedOperationException(); }
      @Override public List<Object> getMatchedResources() { throw new UnsupportedOperationException(); }
      @Override public URI resolve(URI uri) { throw new UnsupportedOperationException(); }
      @Override public URI relativize(URI uri) { throw new UnsupportedOperationException(); }
    };
  }
}

package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.AgroalPoolInterceptor;
import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The #131 boundary on the fused-entity REST read: a bound user reads only entities
 * carrying a granted scope tag; a scopeless entity (already "not discoverable" for the
 * RAG) is invisible to a bound user; unscoped users are untouched. The 403 fires before
 * any data access — the resource under test has NO EntityManager, so reaching the query
 * would fail the test loudly.
 */
class EntityScopeBindingUnitTest {

  /**
   * {@code UriInfoImpl} has no usable no-arg constructor (it needs a live
   * {@code ResteasyReactiveRequestContext}), so the gate tests use this minimal stub instead.
   * The gate fires before any UriInfo use; the pass-the-gate tests only need
   * {@link #getQueryParameters()}.
   */
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

  private static EntityManifest entity(String name, List<String> scopes) {
    return new EntityManifest("Entity", name, List.of("code"),
        List.of(new EntityManifest.Source("connector.t_src", Map.of("code", "code"))),
        scopes, null, Map.of());
  }

  private static EntityReadResource resourceServing(EntityManifest m) {
    EntityReadResource res = new EntityReadResource();
    res.registry = new EntityRegistry() {
      @Override public EntityManifest find(String name) { return m; }
    };
    return res;                                     // em stays null: the gate must fire first
  }

  @BeforeEach
  void tenant() { CurrentTenantHolder.set(1L); }

  @AfterEach
  void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

  @SuppressWarnings("unchecked")
  private static String errorOf(Response r) {
    return String.valueOf(((Map<String, Object>) r.getEntity()).get("error"));
  }

  @Test
  void bound_user_foreign_entity_is_403() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    Response r = resourceServing(entity("customer", List.of("clients")))
        .read("customer", emptyUriInfo(), 100, 0);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  /**
   * A refused read must not be recorded as a successful access — recording it would put an
   * access in the audit trail that never happened, which is worse than not recording at all.
   * {@code EntityReadResource.read} places its audit call after the query actually runs, so a
   * 403 must never even open the audit connection; this pins that at the finest grain available
   * — the poisoned datasource fails the test the instant {@code getConnection()} is called.
   */
  @Test
  void a_403_refusal_never_opens_an_audit_connection() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityReadResource res = resourceServing(entity("customer", List.of("clients")));
    PoisonedAdminDataSource poison = new PoisonedAdminDataSource();
    res.adminDs = poison;

    Response r = res.read("customer", emptyUriInfo(), 100, 0);

    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(poison.connectionRequested)
        .as("a refused read must never open the audit connection").isFalse();
  }

  /**
   * The 404 twin of the proof above — {@code registry.find} returning null is the earliest
   * refusal in {@code read()}, well before the scope gate or any query. Confirms the audit call
   * cannot fire even for that first, simplest refusal.
   */
  @Test
  void a_404_unknown_entity_never_opens_an_audit_connection() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    EntityReadResource res = resourceServing(null);
    PoisonedAdminDataSource poison = new PoisonedAdminDataSource();
    res.adminDs = poison;

    Response r = res.read("nope", emptyUriInfo(), 100, 0);

    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(poison.connectionRequested)
        .as("a 404 must never open the audit connection").isFalse();
  }

  /**
   * {@code getConnection()} always throws and records that it was called; every other member is
   * unused by the code under test and throws loudly if that ever changes.
   */
  private static final class PoisonedAdminDataSource implements AgroalDataSource {
    boolean connectionRequested = false;

    @Override public Connection getConnection() throws SQLException {
      connectionRequested = true;
      throw new SQLException("the audit connection must never be opened for a refused read");
    }
    @Override public Connection getConnection(String username, String password) throws SQLException {
      connectionRequested = true;
      throw new SQLException("the audit connection must never be opened for a refused read");
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
  }

  /**
   * The frozen cross-language contract. The PWA (and {@code lm}, and the OTK oracle) branch on
   * these exact bytes — not on the status alone — so the refusal is pinned at the wire shape,
   * serialized the way the resource layer would serialize it.
   */
  @Test
  void the_refusal_wire_contract_is_frozen() throws Exception {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    Response r = resourceServing(entity("customer", List.of("clients")))
        .read("customer", emptyUriInfo(), 100, 0);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(new ObjectMapper().writeValueAsString(r.getEntity()))
        .isEqualTo("{\"error\":\"scope not granted\"}");
  }

  @Test
  void bound_user_scopeless_entity_is_403() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    Response r = resourceServing(entity("orphan", List.of()))
        .read("orphan", emptyUriInfo(), 100, 0);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  @Test
  void bound_user_granted_entity_passes_the_gate() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityReadResource res = resourceServing(entity("articles", List.of("stock")));
    // past the gate the resource needs its EntityManager, which this test leaves null on
    // purpose — the helpful-NPE naming this.em is the positive proof the gate opened
    // (a wrong 403 would return normally and fail this assertion).
    assertThatThrownBy(() -> res.read("articles", emptyUriInfo(), 100, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.em");
  }

  @Test
  void unscoped_user_is_untouched() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    EntityReadResource res = resourceServing(entity("customer", List.of("clients")));
    // past the gate the resource needs its EntityManager, which this test leaves null on
    // purpose — the helpful-NPE naming this.em is the positive proof the gate opened
    // (a wrong 403 would return normally and fail this assertion).
    assertThatThrownBy(() -> res.read("customer", emptyUriInfo(), 100, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.em");
  }
}

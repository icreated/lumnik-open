package io.lumnik.hub.view;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #131 boundary on the kind:View surface: a bound user (≥1 scope: role) reads only
 * granted scopes; unscoped users are untouched. Uniform refusal: 403 "scope not granted".
 * Plain unit tests — the fake card store returning empty proves a granted/unscoped request
 * passes the gate (it reaches the store and gets the honest 404), while a foreign scope
 * never reaches the store at all.
 */
class ViewScopeBindingUnitTest {

  private static final String CLIENTS_VIEW = """
      apiVersion: apps.lumnik.io/v1
      kind: View
      metadata:
        name: clients-desk
      spec:
        scope: clients
        table: connector.v_customer
        title: "Clients"
        columns: [customer_code]
      """;

  private final SchemaCardStore emptyStore = new SchemaCardStore() {
    @Override public Optional<SchemaCard> load(long tenantId, String scope) {
      return Optional.empty();
    }
  };

  @BeforeEach
  void tenant() { CurrentTenantHolder.set(1L); }

  @AfterEach
  void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

  private static void bindTo(String... scopes) {
    var roles = new HashSet<String>(Set.of("lm_user"));
    for (String s : scopes) roles.add("scope:" + s);
    CurrentRolesHolder.set(roles);
  }

  @SuppressWarnings("unchecked")
  private static String errorOf(Response r) {
    return String.valueOf(((Map<String, Object>) r.getEntity()).get("error"));
  }

  // ---- render ----

  @Test
  void render_foreign_scope_is_403_for_a_bound_user() {
    bindTo("stock");
    ViewRenderResource res = new ViewRenderResource();   // no deps: the gate must fire before the store
    Response r = res.render(List.of(), null, null, CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  @Test
  void render_foreign_scope_is_403_even_with_a_cursor_in_hand() {
    // A cursor authorizes nothing: the scope gate fires before the cursor is even opened, so a
    // valid-looking (or garbage) token neither bypasses it nor changes its answer.
    bindTo("stock");
    ViewRenderResource res = new ViewRenderResource();   // no deps: neither store nor cursors are reached
    Response r = res.render(List.of(), 50, "any-token", CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  @Test
  void render_granted_scope_passes_the_gate() {
    bindTo("clients");
    ViewRenderResource res = new ViewRenderResource();
    res.cards = emptyStore;
    Response r = res.render(List.of(), null, null, CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(404);            // honest "no schema" — the gate let it through
  }

  @Test
  void render_granted_scope_with_whitespace_is_not_denied() {
    // spec.scope with accidental whitespace is normalized at the parser — a bound
    // user granted 'clients' must pass the gate, not get a lying 403.
    bindTo("clients");
    ViewRenderResource res = new ViewRenderResource();
    res.cards = emptyStore;
    Response r = res.render(List.of(), null, null, CLIENTS_VIEW.replace("scope: clients", "scope: \" clients \""));
    assertThat(r.getStatus()).isEqualTo(404);            // past the gate, honest no-schema
  }

  @Test
  void render_unscoped_user_is_untouched() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    ViewRenderResource res = new ViewRenderResource();
    res.cards = emptyStore;
    Response r = res.render(List.of(), null, null, CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(404);
  }

  // ---- detail ----

  @Test
  void detail_foreign_scope_is_403_for_a_bound_user() {
    bindTo("stock");
    ViewDetailResource res = new ViewDetailResource();
    Response r = res.detail("C-100", CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  @Test
  void detail_granted_scope_passes_the_gate() {
    bindTo("clients");
    ViewDetailResource res = new ViewDetailResource();
    res.cards = emptyStore;
    Response r = res.detail("C-100", CLIENTS_VIEW);
    assertThat(r.getStatus()).isEqualTo(404);
  }

  // ---- generate ----

  @Test
  void generate_foreign_scope_is_403_for_a_bound_user() {
    bindTo("stock");
    ViewGenerateResource res = new ViewGenerateResource();
    Response r = res.generate(new ViewGenerateResource.GenerateRequest("clients", "les clients gold"));
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(errorOf(r)).isEqualTo("scope not granted");
  }

  @Test
  void generate_granted_scope_passes_the_gate() {
    bindTo("clients");
    ViewGenerateResource res = new ViewGenerateResource();
    res.cards = emptyStore;
    Response r = res.generate(new ViewGenerateResource.GenerateRequest("clients", "les clients gold"));
    assertThat(r.getStatus()).isEqualTo(404);
  }
}

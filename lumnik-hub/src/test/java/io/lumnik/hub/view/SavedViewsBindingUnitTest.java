package io.lumnik.hub.view;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.security.RequiresPermission;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Scope-bound reads over the saved-view store — the PWA's discovery surface. */
class SavedViewsBindingUnitTest {

  static final class FakeStore extends SavedViewStore {
    Set<String> lastListScopes = Set.of("SENTINEL");
    String deletedName;
    @Override public List<Summary> list(Set<String> scopes) {
      lastListScopes = scopes;
      return List.of(new Summary("clients-desk", "clients", "Clients"));
    }
    @Override public Optional<Row> find(String name) {
      return "clients-desk".equals(name)
          ? Optional.of(new Row("clients-desk", "clients", "Clients", "yaml-here"))
          : Optional.empty();
    }
    @Override public Optional<String> findScope(String name) {
      return find(name).map(Row::scope);
    }
    @Override public boolean delete(String name) {
      deletedName = name;
      return true;
    }
  }

  @AfterEach
  void clear() { CurrentRolesHolder.clear(); }

  private static SavedViewsResource res(FakeStore store) {
    SavedViewsResource r = new SavedViewsResource();
    r.store = store;
    r.audit = new ViewApplyContractUnitTest.RecordingAudit();
    return r;
  }

  @Test
  void door_contract_lm_user_own_path() {
    Path p = SavedViewsResource.class.getAnnotation(Path.class);
    assertThat(p.value()).isEqualTo("/api/views");
    RequiresPermission perm = SavedViewsResource.class.getAnnotation(RequiresPermission.class);
    assertThat(perm.value()).containsExactly("lm_user");
  }

  @Test
  void unscoped_user_lists_all() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    FakeStore store = new FakeStore();
    Response r = res(store).list(null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(store.lastListScopes).isNull();          // null = all, straight to the store
  }

  @Test
  void bound_user_lists_only_granted_scopes() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock", "scope:achats"));
    FakeStore store = new FakeStore();
    res(store).list(null);
    assertThat(store.lastListScopes).containsExactlyInAnyOrder("stock", "achats");
  }

  @Test
  void scope_param_narrows_but_never_escapes_the_grant() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    FakeStore store = new FakeStore();
    Response ok = res(store).list("stock");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(store.lastListScopes).containsExactly("stock");

    Response denied = res(store).list("clients");
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(((Map<String,Object>) denied.getEntity()).get("error")).isEqualTo("scope not granted");
  }

  @Test
  void get_returns_yaml_for_granted_403_for_foreign_404_for_absent() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:clients"));
    FakeStore store = new FakeStore();
    Response ok = res(store).get("clients-desk");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(ok.getEntity()).isEqualTo("yaml-here");
    assertThat(ok.getMediaType().toString()).isEqualTo("application/yaml");

    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    Response denied = res(store).get("clients-desk");
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(((Map<String,Object>) denied.getEntity()).get("error")).isEqualTo("scope not granted");

    Response absent = res(store).get("no-such");
    assertThat(absent.getStatus()).isEqualTo(404);
  }

  // ---- delete: the gesture #487 gave kind:Process, missing on kind:View (#507) ----

  @Test
  void delete_method_carries_its_own_mutating_permission() {
    // The class default is lm_user (read-only); a DELETE is a mutation and must not ride it.
    RequiresPermission perm;
    try {
      perm = SavedViewsResource.class.getMethod("delete", String.class)
          .getAnnotation(RequiresPermission.class);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
    assertThat(perm).isNotNull();
    assertThat(perm.value()).containsExactly("lm_integrator");
  }

  @Test
  void delete_of_a_granted_scope_removes_it() {
    CurrentRolesHolder.set(Set.of("lm_integrator", "scope:clients"));
    FakeStore store = new FakeStore();
    Response r = res(store).delete("clients-desk");
    assertThat(r.getStatus()).isEqualTo(204);
    assertThat(store.deletedName).isEqualTo("clients-desk");
  }

  @Test
  void delete_of_a_foreign_scope_is_refused_before_the_row_is_touched() {
    CurrentRolesHolder.set(Set.of("lm_integrator", "scope:stock"));
    FakeStore store = new FakeStore();
    Response r = res(store).delete("clients-desk");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(((Map<String,Object>) r.getEntity()).get("error")).isEqualTo("scope not granted");
    assertThat(store.deletedName).as("refused before the store is ever touched").isNull();
  }

  @Test
  void delete_of_an_unknown_view_is_404_not_403() {
    CurrentRolesHolder.set(Set.of("lm_integrator"));
    FakeStore store = new FakeStore();
    Response r = res(store).delete("no-such");
    assertThat(r.getStatus()).isEqualTo(404);
  }
}

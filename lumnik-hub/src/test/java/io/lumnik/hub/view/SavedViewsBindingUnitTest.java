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
    @Override public List<Summary> list(Set<String> scopes) {
      lastListScopes = scopes;
      return List.of(new Summary("clients-desk", "clients", "Clients"));
    }
    @Override public Optional<Row> find(String name) {
      return "clients-desk".equals(name)
          ? Optional.of(new Row("clients-desk", "clients", "Clients", "yaml-here"))
          : Optional.empty();
    }
  }

  @AfterEach
  void clear() { CurrentRolesHolder.clear(); }

  private static SavedViewsResource res(FakeStore store) {
    SavedViewsResource r = new SavedViewsResource();
    r.store = store;
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
}

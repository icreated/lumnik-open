package io.lumnik.hub.rag;

import io.lumnik.identity.user.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagScopeResolverTest {

  private final RagScopeResolver resolver = new RagScopeResolver();

  @Test
  void override_flag_wins_over_everything() {
    User u = new User(1L, "alice", "Alice");
    u.setDefaultScope("paie");
    List<String> scope = resolver.resolve(u, "stock", "compta");
    assertThat(scope).containsExactly("compta");
  }

  @Test
  void override_all_returns_empty_list() {
    User u = new User(1L, "alice", "Alice");
    u.setDefaultScope("paie");
    List<String> scope = resolver.resolve(u, "stock", "all");
    assertThat(scope).isEmpty();
  }

  @Test
  void tui_context_used_when_no_override() {
    User u = new User(1L, "alice", "Alice");
    u.setDefaultScope("paie");
    List<String> scope = resolver.resolve(u, "stock", null);
    assertThat(scope).containsExactly("stock");
  }

  @Test
  void user_default_used_when_no_override_no_context() {
    User u = new User(1L, "alice", "Alice");
    u.setDefaultScope("paie");
    List<String> scope = resolver.resolve(u, null, null);
    assertThat(scope).containsExactly("paie");
  }

  @Test
  void falls_back_to_system_when_nothing_set() {
    User u = new User(1L, "alice", "Alice");
    List<String> scope = resolver.resolve(u, null, null);
    assertThat(scope).containsExactly("system");
  }
}

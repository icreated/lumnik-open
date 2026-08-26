package io.lumnik.hub.view;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The saved-view store: deployment-global rows (the entity_manifest posture), direct SQL. */
@QuarkusTest
class SavedViewStoreIT {

  @Inject SavedViewStore store;

  private static final String YAML = "apiVersion: apps.lumnik.io/v1\nkind: View\n";

  @Test
  void upsert_find_roundtrip_and_overwrite() {
    store.upsert("it-clients-desk", "clients", "Clients", YAML + "# v1");
    var row = store.find("it-clients-desk");
    assertThat(row).isPresent();
    assertThat(row.get().scope()).isEqualTo("clients");
    assertThat(row.get().title()).isEqualTo("Clients");
    assertThat(row.get().yaml()).endsWith("# v1");

    store.upsert("it-clients-desk", "clients", "Clients v2", YAML + "# v2");
    var again = store.find("it-clients-desk");
    assertThat(again.get().title()).isEqualTo("Clients v2");
    assertThat(again.get().yaml()).endsWith("# v2");
    assertThat(store.list(null).stream()
        .filter(s -> s.name().equals("it-clients-desk")).count()).isEqualTo(1);
  }

  @Test
  void list_filters_by_scope_set() {
    store.upsert("it-stock-articles", "stock", "Articles", YAML);
    store.upsert("it-achats-cmd", "achats", "Commandes", YAML);

    List<SavedViewStore.Summary> bound = store.list(Set.of("stock"));
    assertThat(bound).extracting(SavedViewStore.Summary::name).contains("it-stock-articles");
    assertThat(bound).extracting(SavedViewStore.Summary::scope).containsOnly("stock");

    assertThat(store.list(Set.of())).isEmpty();                       // bound to nothing = sees nothing
    assertThat(store.list(null).stream().map(SavedViewStore.Summary::name))
        .contains("it-stock-articles", "it-achats-cmd");              // unscoped = all
  }

  @Test
  void find_absent_is_empty() {
    assertThat(store.find("it-no-such-view")).isEmpty();
  }

  @Test
  void upsert_normalizes_case_so_every_caller_reaches_the_same_row() {
    // the store owns normalization on BOTH sides — a mixed-case upsert from any
    // future caller must land on the row find() reaches.
    store.upsert("IT-MiXeD-Desk", "clients", "Mixed", YAML);
    assertThat(store.find("it-mixed-desk")).isPresent();
    assertThat(store.find("it-mixed-desk").get().name()).isEqualTo("it-mixed-desk");
  }

  @Test
  void find_is_case_insensitive_like_the_entity_pattern() {
    // apply lowercases names on write (entity_manifest rule) — find must lowercase on
    // read too, or a hand-typed 'Clients-Desk' 404s while 'clients-desk' exists.
    store.upsert("it-case-desk", "clients", "Case", YAML);
    assertThat(store.find("IT-Case-DESK")).isPresent();
  }
}

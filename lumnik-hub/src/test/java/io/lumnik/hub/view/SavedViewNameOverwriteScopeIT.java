package io.lumnik.hub.view;

import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-overwrite guard's scope predicate, asked of the STORE rather than of the door.
 *
 * <p>{@code ViewApplyResource} calls {@code findScope(name)} and then {@code upsert} — two
 * connections, two
 * transactions, and a window in between where another transaction can take that name under a scope
 * the caller does not hold. The property that survives that race is not "the door refuses" but
 * <b>the store refuses on its own</b>, so the write is called directly here, as if the ordered check
 * had been jumped entirely — which is precisely what the race does. The full reasoning lives in
 * {@code AppNameOverwriteScopeIT}; this is its kind:View sibling (#582).
 *
 * <p>Each test asserts the pre-existing row as well as the refusal: a refusal returned over a row
 * that was quietly rewritten anyway would be worse than no refusal at all.
 */
@QuarkusTest
class SavedViewNameOverwriteScopeIT {

  private static final String OWNER = "proprietaire-vue-582";
  private static final String INTRUDER = "intrus-vue-582";
  private static final String YAML = "apiVersion: apps.lumnik.io/v1\nkind: View\n";

  @Inject SavedViewStore store;

  /** Puts the name in {@link #OWNER}'s hands, as an unscoped integrator legitimately would. */
  private SavedViewStore.Row given(String name) {
    store.upsert(name, OWNER, "Owned", YAML + "# owner", null);
    return store.find(name).orElseThrow();
  }

  @Test
  void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
    SavedViewStore.Row before = given("vue-nom-582");

    ScopeGuardedUpsert outcome =
        store.upsert("vue-nom-582", INTRUDER, "Stolen", YAML + "# stolen", Set.of(INTRUDER));

    assertThat(outcome)
        .as("the conflict arm's own WHERE refuses, with no help from the resource's ordered "
            + "findScope check")
        .isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);

    assertThat(store.find("vue-nom-582").orElseThrow())
        .as("refused AND untouched — the row must not have been re-homed on the way out")
        .isEqualTo(before);
  }

  @Test
  void anUnscopedCallerStillOverwritesFreely() {
    // Half a rule proven is a rule that could be enforced by refusing everything.
    given("vue-relais-582");

    ScopeGuardedUpsert outcome =
        store.upsert("vue-relais-582", INTRUDER, "Re-homed", YAML + "# rehomed", null);

    assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);

    SavedViewStore.Row after = store.find("vue-relais-582").orElseThrow();
    assertThat(after.scope()).isEqualTo(INTRUDER);
    assertThat(after.title()).isEqualTo("Re-homed");
  }

  @Test
  void aBoundCallerStillCreatesAFreeName() {
    // The third outcome of INSERT … ON CONFLICT: no conflict at all. Zero rows affected is read
    // as a refusal, so an ordinary insert must never be mistaken for one.
    ScopeGuardedUpsert outcome =
        store.upsert("vue-neuve-582", INTRUDER, "Neuve", YAML + "# neuve", Set.of(INTRUDER));

    assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
    assertThat(store.find("vue-neuve-582").orElseThrow().scope()).isEqualTo(INTRUDER);
  }

  @Test
  void aBoundCallerMayOverwriteANameInsideItsGrants() {
    // The guard must not refuse the ordinary case it exists to permit: the same métier updating
    // its own view. Without this, "refuse everything" would pass the three tests above.
    given("vue-sienne-582");
    ScopeGuardedUpsert promote =
        store.upsert("vue-sienne-582", OWNER, "Updated", YAML + "# v2", Set.of(OWNER));

    assertThat(promote).isInstanceOf(ScopeGuardedUpsert.Applied.class);
    assertThat(store.find("vue-sienne-582").orElseThrow().title()).isEqualTo("Updated");
  }
}

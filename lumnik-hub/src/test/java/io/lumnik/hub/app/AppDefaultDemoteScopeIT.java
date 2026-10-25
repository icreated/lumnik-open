package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default demote's scope predicate, asked of the STORE rather than of the door.
 *
 * <p>{@code AppApplyResource} checks the default holder's scope and then calls {@code upsert} —
 * two connections, two transactions, and a window in between where an unscoped integrator can
 * promote a different scope's app. The bound caller's demote would then land on a row it can
 * neither read nor write. The property that survives that race is not "the door refuses" but
 * <b>the store refuses on its own</b>, so that is what is measured here: {@code upsert} is called
 * directly, as if the ordered check had been jumped entirely — which is precisely what the race
 * does.
 *
 * <p>A racy test would prove nothing repeatable; this one is deterministic and asserts the same
 * guarantee.
 */
@QuarkusTest
class AppDefaultDemoteScopeIT {

    @Inject AppRegistrationStore store;

    /** The current default's row — read, never assumed: every @QuarkusTest in the module shares one
     *  database and JUnit guarantees no order between classes. */
    private AppRegistrationStore.Row currentDefault() {
        return store.find(store.findDefaultName().orElseThrow()).orElseThrow();
    }

    private static AppManifest app(String name, String scope, boolean isDefault) {
        return new AppManifest(name, scope, name, "event", "#8b7ff0", "#14121f", isDefault);
    }

    @Test
    void aBoundCallerCannotDemoteADefaultHeldOutsideItsGrants() {
        AppRegistrationStore.Row holder = currentDefault();
        // By construction not the holder's scope, and still a legal scope name — the assertion must
        // not depend on which app happens to be the default when this class runs.
        String foreign = "not-" + holder.scope();

        AppRegistrationStore.Upserted outcome = store.upsert(
            app("vitrine-toctou", foreign, true),
            manifest("vitrine-toctou", foreign, "  default: true\n"),
            Set.of(foreign));

        assertThat(outcome)
            .as("the demote's own WHERE refuses, with no help from the resource's ordered check")
            .isInstanceOf(AppRegistrationStore.Upserted.DefaultHeldOutOfScope.class);

        assertThat(store.findDefaultName()).contains(holder.name());
        assertThat(store.find(holder.name()).orElseThrow().isDefault()).isTrue();
        assertThat(store.find("vitrine-toctou"))
            .as("the whole unit of work rolled back, insert included")
            .isEmpty();
    }

    @Test
    void anUnscopedCallerStillDemotesFreely() {
        // Half a rule proven is a rule that could be enforced by refusing everything.
        AppRegistrationStore.Row previous = currentDefault();
        try {
            AppRegistrationStore.Upserted outcome = store.upsert(
                app("relais-toctou", "relais", true),
                manifest("relais-toctou", "relais", "  default: true\n"),
                null);

            assertThat(outcome)
                .isEqualTo(new AppRegistrationStore.Upserted.Applied(Optional.of(previous.name())));
            assertThat(store.findDefaultName()).contains("relais-toctou");
            assertThat(store.find(previous.name()).orElseThrow().isDefault()).isFalse();
        } finally {
            // Restores from the ROW, so the theme AppRegistrationSeedIT pins travels back intact.
            store.upsert(
                new AppManifest(previous.name(), previous.scope(), previous.title(),
                    previous.icon(), previous.accentColor(), previous.backgroundColor(), true, previous.tabs()),
                manifest(previous.name(), previous.scope(), "  default: true\n"),
                null);
        }
        assertThat(store.findDefaultName()).contains(previous.name());
    }
}

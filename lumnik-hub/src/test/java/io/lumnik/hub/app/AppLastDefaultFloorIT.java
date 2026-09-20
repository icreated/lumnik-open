package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "at least one default" floor, asked of the STORE rather than of the door — the third sibling
 * of {@link AppDefaultDemoteScopeIT} and {@link AppNameOverwriteScopeIT}, for the one invariant of
 * the three that is not about scope.
 *
 * <p>{@code AppApplyResource} reads {@code findDefaultName()} and refuses only when the applied
 * name IS the current default. That read takes its own autocommit connection, so it answers about
 * an instant that has already passed: a concurrent apply can promote this very name to default
 * before {@code upsert}'s conflict arm runs, and the arm would then clear the flag the name just
 * acquired and commit a deployment with zero defaults — an absent {@code ?m=} resolving to
 * nothing, which is the gap kind:App exists to close, reopened from the other side. The property
 * that survives that race is not "the door refuses" but <b>the store refuses on its own</b>, so
 * {@code upsert} is called directly here, as if the ordered check had been jumped entirely — which
 * is precisely what the race does.
 *
 * <p>The grants passed in DO include the row's own scope, so the conflict arm's scope predicate
 * cannot be what refuses: only the floor clause can. And the row is read back afterwards, because
 * a refusal returned over a flag that was cleared anyway would be worse than no refusal at all.
 */
@QuarkusTest
class AppLastDefaultFloorIT {

    private static final String SCOPE = "plancher-toctou";

    @Inject AppRegistrationStore store;

    /** The current default's row — read, never assumed: every @QuarkusTest in the module shares one
     *  database and JUnit guarantees no order between classes. */
    private AppRegistrationStore.Row currentDefault() {
        return store.find(store.findDefaultName().orElseThrow()).orElseThrow();
    }

    private static AppManifest app(String name, String title, boolean isDefault) {
        return new AppManifest(name, SCOPE, title, "event", "#8b7ff0", "#14121f", isDefault);
    }

    /** Puts the flag on {@code name}, as an unscoped integrator legitimately would — which demotes
     *  whoever held it, hence the restore every caller owes in a finally. */
    private AppRegistrationStore.Row givenItHoldsTheDefault(String name) {
        store.upsert(app(name, "Plancher", true), manifest(name, SCOPE, "  default: true\n"), null);
        return store.find(name).orElseThrow();
    }

    /** Hands the flag back to the app that held it before this class ran, from its own ROW so the
     *  theme AppRegistrationSeedIT pins travels back intact. */
    private void restore(AppRegistrationStore.Row previous) {
        store.upsert(
            new AppManifest(previous.name(), previous.scope(), previous.title(), previous.icon(),
                previous.accentColor(), previous.backgroundColor(), true),
            manifest(previous.name(), previous.scope(), "  default: true\n"),
            null);
    }

    @Test
    void theLastDefaultCannotBeDemotedEvenByACallerWhoHoldsItsScope() {
        AppRegistrationStore.Row previous = currentDefault();
        try {
            AppRegistrationStore.Row before = givenItHoldsTheDefault("vitrine-plancher");
            assertThat(before.isDefault()).as("the fixture the assertion depends on").isTrue();

            AppRegistrationStore.Upserted outcome = store.upsert(
                app("vitrine-plancher", "Démis", false),
                manifest("vitrine-plancher", SCOPE, ""),
                Set.of(SCOPE));

            assertThat(outcome)
                .as("the conflict arm's own WHERE refuses, with no help from the resource's "
                    + "ordered findDefaultName check")
                .isInstanceOf(AppRegistrationStore.Upserted.LastDefaultWouldBeRemoved.class);

            assertThat(store.find("vitrine-plancher").orElseThrow())
                .as("refused AND untouched — the flag must not have been cleared on the way out")
                .isEqualTo(before);
            assertThat(store.findDefaultName())
                .as("an absent ?m= must still resolve to something after the refusal")
                .contains("vitrine-plancher");
        } finally {
            restore(previous);
        }
        assertThat(store.findDefaultName()).contains(previous.name());
    }

    @Test
    void anAppThatIsNotTheDefaultIsStillReAppliedWithDefaultFalse() {
        // The control. Without it the guard is only proven to FIRE, not to fire only when it
        // should — refusing every `default: false` would pass the test above just as well.
        store.upsert(app("relais-plancher", "Relais", false),
            manifest("relais-plancher", SCOPE, ""), null);

        AppRegistrationStore.Upserted outcome = store.upsert(
            app("relais-plancher", "Retouché", false),
            manifest("relais-plancher", SCOPE, ""),
            Set.of(SCOPE));

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Upserted.Applied.class);
        assertThat(store.find("relais-plancher").orElseThrow().title()).isEqualTo("Retouché");
    }

    @Test
    void theDefaultIsStillReAppliedWhenItKeepsTheFlag() {
        // The other half of the control: the floor refuses the flag being REMOVED, not every write
        // that lands on the row carrying it. Editing the current default's title must still work.
        AppRegistrationStore.Row previous = currentDefault();
        try {
            givenItHoldsTheDefault("enseigne-plancher");

            AppRegistrationStore.Upserted outcome = store.upsert(
                app("enseigne-plancher", "Retouché", true),
                manifest("enseigne-plancher", SCOPE, "  default: true\n"),
                Set.of(SCOPE));

            assertThat(outcome).isInstanceOf(AppRegistrationStore.Upserted.Applied.class);
            AppRegistrationStore.Row after = store.find("enseigne-plancher").orElseThrow();
            assertThat(after.title()).isEqualTo("Retouché");
            assertThat(after.isDefault()).isTrue();
        } finally {
            restore(previous);
        }
    }
}

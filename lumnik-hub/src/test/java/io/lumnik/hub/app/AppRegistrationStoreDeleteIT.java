package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppRegistrationStore#delete}'s own contract, asked of the STORE the way
 * {@link AppLastDefaultFloorIT} asks {@code upsert}'s — the delete-door twin. The floor is
 * enforced in the DELETE statement's own WHERE, not by a read-then-act check, so it holds even
 * called directly with grants that include the row's own scope: nothing here plays the role of
 * the door's ordered pre-check.
 */
@QuarkusTest
class AppRegistrationStoreDeleteIT {

    private static final String SCOPE = "plancher-suppr";

    @Inject AppRegistrationStore store;

    private static AppManifest app(String name, boolean isDefault) {
        return new AppManifest(name, SCOPE, "Suppr", "event", "#8b7ff0", "#14121f", isDefault);
    }

    @Test
    void deletesAnOrdinaryAppAndReportsRemoved() {
        store.upsert(app("kiosque-suppr", false), manifest("kiosque-suppr", SCOPE, ""), null);

        AppRegistrationStore.Deleted outcome = store.delete("kiosque-suppr", Set.of(SCOPE));

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Deleted.Removed.class);
        assertThat(store.find("kiosque-suppr")).isEmpty();
    }

    @Test
    void refusesAnUnknownName() {
        AppRegistrationStore.Deleted outcome = store.delete("jamais-vu-suppr", Set.of(SCOPE));

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Deleted.NotFound.class);
    }

    @Test
    void refusesANameHeldOutsideTheCallersScope() {
        store.upsert(app("cave-suppr", false), manifest("cave-suppr", SCOPE, ""), null);

        AppRegistrationStore.Deleted outcome = store.delete("cave-suppr", Set.of("autre-metier"));

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Deleted.HeldOutOfScope.class);
        assertThat(store.find("cave-suppr")).as("refused AND untouched").isPresent();
    }

    @Test
    void theLastDefaultCannotBeDeletedEvenByACallerWhoHoldsItsScope() {
        AppRegistrationStore.Row previous =
            store.find(store.findDefaultName().orElseThrow()).orElseThrow();
        try {
            store.upsert(app("vitrine-suppr", true), manifest("vitrine-suppr", SCOPE, "  default: true\n"),
                null);

            AppRegistrationStore.Deleted outcome = store.delete("vitrine-suppr", Set.of(SCOPE));

            assertThat(outcome)
                .as("the DELETE statement's own WHERE refuses, with no help from a door's ordered "
                    + "pre-check")
                .isInstanceOf(AppRegistrationStore.Deleted.IsTheLastDefault.class);
            assertThat(store.find("vitrine-suppr"))
                .as("refused AND untouched — the flag must not have been cleared on the way out")
                .isPresent();
            assertThat(store.findDefaultName())
                .as("an absent ?m= must still resolve to something after the refusal")
                .contains("vitrine-suppr");
        } finally {
            store.upsert(
                new AppManifest(previous.name(), previous.scope(), previous.title(), previous.icon(),
                    previous.accentColor(), previous.backgroundColor(), true, previous.tabs()),
                manifest(previous.name(), previous.scope(), "  default: true\n"),
                null);
            store.delete("vitrine-suppr", null);
        }
    }
}

package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-overwrite guard's scope predicate, asked of the STORE rather than of the door — the
 * sibling of {@link AppDefaultDemoteScopeIT}, for the other address an apply writes to.
 *
 * <p>{@code AppApplyResource} calls {@code findScope(name)} and then {@code upsert} — two
 * connections, two transactions, and a window in between where another transaction can take that
 * name under a scope the caller does not hold. The unconditional {@code ON CONFLICT … DO UPDATE}
 * would then re-home it, which is exactly the cross-scope write {@code ApplyScopeBoundary} exists
 * to refuse. The property that survives that race is not "the door refuses" but <b>the store
 * refuses on its own</b>, so {@code upsert} is called directly here, as if the ordered check had
 * been jumped entirely — which is precisely what the race does.
 *
 * <p>A racy test would prove nothing repeatable; this one is deterministic and asserts the same
 * guarantee. It asserts the pre-existing row as well as the refusal: a refusal returned over a row
 * that was quietly rewritten anyway would be worse than no refusal at all.
 */
@QuarkusTest
class AppNameOverwriteScopeIT {

    private static final String OWNER = "proprietaire-toctou";
    private static final String INTRUDER = "intrus-toctou";

    @Inject AppRegistrationStore store;

    private static AppManifest app(String name, String scope, String title) {
        return new AppManifest(name, scope, title, "event", "#8b7ff0", "#14121f", false);
    }

    /** Puts the name in {@link #OWNER}'s hands, as an unscoped integrator legitimately would. */
    private AppRegistrationStore.Row given(String name) {
        store.upsert(app(name, OWNER, "Owned"), manifest(name, OWNER, ""), null);
        return store.find(name).orElseThrow();
    }

    @Test
    void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
        AppRegistrationStore.Row before = given("vitrine-name-toctou");

        AppRegistrationStore.Upserted outcome = store.upsert(
            app("vitrine-name-toctou", INTRUDER, "Stolen"),
            manifest("vitrine-name-toctou", INTRUDER, ""),
            Set.of(INTRUDER));

        assertThat(outcome)
            .as("the conflict arm's own WHERE refuses, with no help from the resource's ordered "
                + "findScope check")
            .isInstanceOf(AppRegistrationStore.Upserted.NameHeldOutOfScope.class);

        assertThat(store.find("vitrine-name-toctou").orElseThrow())
            .as("refused AND untouched — the row must not have been re-homed on the way out")
            .isEqualTo(before);
    }

    @Test
    void anUnscopedCallerStillOverwritesFreely() {
        // Half a rule proven is a rule that could be enforced by refusing everything.
        given("relais-name-toctou");

        AppRegistrationStore.Upserted outcome = store.upsert(
            app("relais-name-toctou", INTRUDER, "Re-homed"),
            manifest("relais-name-toctou", INTRUDER, ""),
            null);

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Upserted.Applied.class);

        AppRegistrationStore.Row after = store.find("relais-name-toctou").orElseThrow();
        assertThat(after.scope()).isEqualTo(INTRUDER);
        assertThat(after.title()).isEqualTo("Re-homed");
    }

    @Test
    void aBoundCallerStillCreatesAFreeName() {
        // The third outcome of INSERT … ON CONFLICT: no conflict at all. Zero rows affected is read
        // as a refusal, so an ordinary insert must never be mistaken for one.
        AppRegistrationStore.Upserted outcome = store.upsert(
            app("neuf-name-toctou", INTRUDER, "Neuf"),
            manifest("neuf-name-toctou", INTRUDER, ""),
            Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(AppRegistrationStore.Upserted.Applied.class);
        assertThat(store.find("neuf-name-toctou").orElseThrow().scope()).isEqualTo(INTRUDER);
    }

    /**
     * #1649: the one refusal that must undo a write. A bound caller's {@code default: true} first demotes the default
     * its grants reach, then the conflict arm refuses the name held outside them. The refusal leaves the transaction as
     * an exception, so the demote rolls back with it; returned as a value, it would commit, and leave no default app.
     */
    @Test
    void aRefusedNameUndoesTheDemoteItsDefaultFlagMade() {
        AppRegistrationStore.Row before = given("vitrine-default-toctou");
        // The current default, read rather than assumed: the caller is granted ITS scope, so the demote reaches it.
        AppRegistrationStore.Row holder = store.find(store.findDefaultName().orElseThrow()).orElseThrow();
        Throwable failure = null;
        try {
            AppRegistrationStore.Upserted outcome = store.upsert(
                new AppManifest("vitrine-default-toctou", holder.scope(), "Stolen", "event", "#8b7ff0", "#14121f", true),
                manifest("vitrine-default-toctou", holder.scope(), "  default: true\n"),
                Set.of(holder.scope()));

            assertThat(outcome).isInstanceOf(AppRegistrationStore.Upserted.NameHeldOutOfScope.class);
            assertThat(store.findDefaultName()).as("the demote rolled back with the refusal").contains(holder.name());
            assertThat(store.find("vitrine-default-toctou").orElseThrow()).isEqualTo(before);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            if (!store.findDefaultName().orElse("").equals(holder.name())) {
                // A regression here must not leave the module's other tests without a default app, nor hide
                // the failure that says so behind one from the restore.
                try {
                    store.upsert(new AppManifest(holder.name(), holder.scope(), holder.title(), holder.icon(),
                            holder.accentColor(), holder.backgroundColor(), true, holder.tabs()),
                        manifest(holder.name(), holder.scope(), "  default: true\n"), null);
                } catch (RuntimeException restoreFailure) {
                    if (failure == null) throw restoreFailure;
                    failure.addSuppressed(restoreFailure);
                }
            }
        }
    }
}

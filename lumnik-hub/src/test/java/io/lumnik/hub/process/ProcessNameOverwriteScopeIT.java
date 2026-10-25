package io.lumnik.hub.process;

import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-overwrite guard's scope predicate, asked of the STORE rather than of the door.
 *
 * <p>{@code ProcessApplyResource} calls {@code findScope(tenant, name)} and then
 * {@code upsert} — two connections, two
 * transactions, and a window in between where another transaction can take that name under a scope
 * the caller does not hold. The property that survives that race is not "the door refuses" but
 * <b>the store refuses on its own</b>, so the write is called directly here, as if the ordered check
 * had been jumped entirely — which is precisely what the race does. The full reasoning lives in
 * {@code AppNameOverwriteScopeIT}; this is its kind:Process sibling (#582).
 *
 * <p>Re-homing a process takes its emitted text with it, so the untouched-row assertion reads the
 * stored yaml as well as the scope.
 */
@QuarkusTest
class ProcessNameOverwriteScopeIT {

    private static final long TENANT = 5821L;
    private static final String OWNER = "proprietaire-proc-582";
    private static final String INTRUDER = "intrus-proc-582";

    @Inject ProcessManifestStore store;

    private static String yaml(String name, String scope) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Process
            metadata:
              name: %s
            spec:
              scope: %s
              on:
                event: data.row.changed
                table: connector.t_proc_582
              when:
                - segment=GOLD
              emit:
                type: decision.%s
                message: "Nouveau"
            """.formatted(name, scope, name);
    }

    private static ProcessManifest parse(String yaml) {
        return new ProcessManifestParser().parse(yaml);
    }

    /** Puts the name in {@link #OWNER}'s hands, as an unscoped integrator legitimately would. */
    private String given(String name) {
        String owned = yaml(name, OWNER);
        store.upsert(TENANT, parse(owned), owned, null);
        return owned;
    }

    @Test
    void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
        String before = given("proc-nom-582");

        String stolen = yaml("proc-nom-582", INTRUDER);
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(stolen), stolen, Set.of(INTRUDER));

        assertThat(outcome)
            .as("the conflict arm's own WHERE refuses, with no help from the resource's ordered "
                + "findScope check")
            .isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);

        assertThat(store.findScope(TENANT, "proc-nom-582"))
            .as("refused AND untouched — the row must not have been re-homed on the way out")
            .contains(OWNER);
        assertThat(store.findYaml(TENANT, "proc-nom-582")).contains(before);
    }

    @Test
    void anUnscopedCallerStillOverwritesFreely() {
        // Half a rule proven is a rule that could be enforced by refusing everything.
        given("proc-relais-582");

        String rehomed = yaml("proc-relais-582", INTRUDER);
        ScopeGuardedUpsert outcome = store.upsert(TENANT, parse(rehomed), rehomed, null);

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findScope(TENANT, "proc-relais-582")).contains(INTRUDER);
    }

    @Test
    void aBoundCallerStillCreatesAFreeName() {
        // The third outcome of INSERT … ON CONFLICT: no conflict at all. Zero rows affected is read
        // as a refusal, so an ordinary insert must never be mistaken for one.
        String fresh = yaml("proc-neuf-582", INTRUDER);
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(fresh), fresh, Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findScope(TENANT, "proc-neuf-582")).contains(INTRUDER);
    }

    @Test
    void aBoundCallerMayOverwriteANameInsideItsGrants() {
        // The guard must not refuse the ordinary case it exists to permit: the same scope updating
        // its own process. Without this, "refuse everything" would pass the three tests above.
        given("proc-sien-582");

        String updated = yaml("proc-sien-582", OWNER) + "# v2\n";
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(updated), updated, Set.of(OWNER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findYaml(TENANT, "proc-sien-582")).contains(updated);
    }
}

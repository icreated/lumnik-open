package io.lumnik.hub.workflow;

import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-overwrite guard's scope predicate, asked of the STORE rather than of the door.
 *
 * <p>{@code WorkflowApplyResource} calls {@code findScope(tenant, name)} and then
 * {@code upsert} — two connections, two
 * transactions, and a window in between where another transaction can take that name under a scope
 * the caller does not hold. The property that survives that race is not "the door refuses" but
 * <b>the store refuses on its own</b>, so the write is called directly here, as if the ordered check
 * had been jumped entirely — which is precisely what the race does. The full reasoning lives in
 * {@code AppNameOverwriteScopeIT}; this is its kind:Workflow sibling (#582).
 *
 * <p>Re-homing a lifecycle takes its chunk and its observation ledger with it.
 *
 * <p>Each test declares its own {@code on.column}: V32's {@code uq_workflow_manifest_tenant_column}
 * admits one lifecycle per column per tenant, and two tests sharing one would collide on that
 * constraint rather than on the property under test.
 */
@QuarkusTest
class WorkflowNameOverwriteScopeIT {

    private static final long TENANT = 5822L;
    private static final String OWNER = "proprietaire-wf-582";
    private static final String INTRUDER = "intrus-wf-582";

    @Inject WorkflowManifestStore store;

    private static String yaml(String name, String scope, String column) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: %s }
            spec:
              scope: %s
              provenance: declared
              on: { table: connector.t_wf_582, column: %s }
              states:
                - { code: A, initial: true }
                - { code: B, terminal: true }
              transitions:
                - { from: [A], to: B }
            """.formatted(name, scope, column);
    }

    private static WorkflowManifest parse(String yaml) {
        return new WorkflowManifestParser().parse(yaml);
    }

    /** Puts the name in {@link #OWNER}'s hands, as an unscoped integrator legitimately would. */
    private String given(String name, String column) {
        String owned = yaml(name, OWNER, column);
        store.upsert(TENANT, parse(owned), owned, null);
        return owned;
    }

    @Test
    void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
        String before = given("wf-nom-582", "statut_nom");

        String stolen = yaml("wf-nom-582", INTRUDER, "statut_nom");
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(stolen), stolen, Set.of(INTRUDER));

        assertThat(outcome)
            .as("the conflict arm's own WHERE refuses, with no help from the resource's ordered "
                + "findScope check")
            .isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);

        assertThat(store.findScope(TENANT, "wf-nom-582"))
            .as("refused AND untouched — the row must not have been re-homed on the way out")
            .contains(OWNER);
        assertThat(store.findYaml(TENANT, "wf-nom-582")).contains(before);
    }

    @Test
    void anUnscopedCallerStillOverwritesFreely() {
        // Half a rule proven is a rule that could be enforced by refusing everything.
        given("wf-relais-582", "statut_relais");

        String rehomed = yaml("wf-relais-582", INTRUDER, "statut_relais");
        ScopeGuardedUpsert outcome = store.upsert(TENANT, parse(rehomed), rehomed, null);

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findScope(TENANT, "wf-relais-582")).contains(INTRUDER);
    }

    @Test
    void aBoundCallerStillCreatesAFreeName() {
        // The third outcome of INSERT … ON CONFLICT: no conflict at all. Zero rows affected is read
        // as a refusal, so an ordinary insert must never be mistaken for one.
        String fresh = yaml("wf-neuf-582", INTRUDER, "statut_neuf");
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(fresh), fresh, Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findScope(TENANT, "wf-neuf-582")).contains(INTRUDER);
    }

    @Test
    void aBoundCallerMayOverwriteANameInsideItsGrants() {
        // The guard must not refuse the ordinary case it exists to permit: the same métier updating
        // its own workflow. Without this, "refuse everything" would pass the three tests above.
        given("wf-sien-582", "statut_sien");

        String updated = yaml("wf-sien-582", OWNER, "statut_sien") + "# v2\n";
        ScopeGuardedUpsert outcome =
            store.upsert(TENANT, parse(updated), updated, Set.of(OWNER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(store.findYaml(TENANT, "wf-sien-582")).contains(updated);
    }
}

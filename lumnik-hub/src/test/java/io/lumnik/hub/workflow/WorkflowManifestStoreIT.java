package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowManifestStoreIT {

    @Inject WorkflowManifestStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-store-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_store_it, column: statut }
          states:
            - { code: A, initial: true }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    @Test
    void upsert_then_list_then_yaml_roundtrip_and_cross_tenant_invisibility() {
        WorkflowManifest m = new WorkflowManifestParser().parse(YAML);
        store.upsert(7001L, m, YAML, null);
        store.upsert(7001L, m, YAML, null); // idempotent upsert

        var mine = store.listByTenant(7001L);
        assertThat(mine).extracting(WorkflowManifestStore.StoredWorkflow::name)
            .contains("wf-store-it");
        assertThat(mine.stream().filter(w -> w.name().equals("wf-store-it")).findFirst().orElseThrow())
            .satisfies(w -> {
                assertThat(w.scope()).isEqualTo("demo");
                assertThat(w.table()).isEqualTo("connector.t_store_it");
                assertThat(w.column()).isEqualTo("statut");
                assertThat(w.enabled()).isTrue();
            });
        assertThat(store.findYaml(7001L, "wf-store-it")).contains(YAML);
        assertThat(store.findYaml(7002L, "wf-store-it")).isEmpty();
        assertThat(store.listByTenant(7002L)).noneMatch(w -> w.name().equals("wf-store-it"));
    }

    /**
     * Review round on #507: neither {@code core.workflow_observation} nor
     * {@code core.workflow_transition} carries a FK/cascade to {@code core.workflow_manifest}
     * (V33's own comment), so a hard delete that only touched the manifest would leave the
     * ledger behind — a name reapplied later would inherit stale {@code since}/{@code
     * alerted_at} and transition tape from the lifecycle that was supposedly deleted "for good".
     */
    @Test
    void delete_purges_the_observation_and_transition_ledger_too() {
        long tenant = 7003L;
        String name = "wf-delete-ledger-it";
        WorkflowManifest m = new WorkflowManifestParser().parse(YAML.replace("wf-store-it", name)
            .replace("t_store_it", "t_delete_ledger_it"));
        store.upsert(tenant, m, YAML, null);

        Jdbc.update(adminDs, "test.seedObservation",
            "INSERT INTO core.workflow_observation "
          + "(tenant_id, workflow_name, doc_key, state, since, first_observed_at) "
          + "VALUES (?, ?, 'CMD-1', 'A', NOW(), NOW())",
            ps -> { ps.setLong(1, tenant); ps.setString(2, name); });
        Jdbc.update(adminDs, "test.seedTransition",
            "INSERT INTO core.workflow_transition "
          + "(tenant_id, workflow_name, doc_key, from_state, to_state, observed_at) "
          + "VALUES (?, ?, 'CMD-1', 'A', 'B', NOW())",
            ps -> { ps.setLong(1, tenant); ps.setString(2, name); });

        store.delete(tenant, name);

        assertThat(store.findYaml(tenant, name)).isEmpty();
        assertThat(ledgerRowCount("core.workflow_observation", tenant, name)).isZero();
        assertThat(ledgerRowCount("core.workflow_transition", tenant, name)).isZero();
    }

    /** #1237: two removals racing past findScope must audit once — the store says which one removed it. */
    @Test
    void delete_says_whether_it_removed_the_manifest() {
        long tenant = 7004L;
        String name = "wf-delete-once-it";
        WorkflowManifest m = new WorkflowManifestParser().parse(YAML.replace("wf-store-it", name)
            .replace("t_store_it", "t_delete_once_it"));
        store.upsert(tenant, m, YAML, null);

        assertThat(store.delete(tenant, name)).isTrue();
        assertThat(store.delete(tenant, name)).isFalse();
    }

    private long ledgerRowCount(String table, long tenant, String name) {
        return Jdbc.queryOne(adminDs, "test.ledgerRowCount",
            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND workflow_name = ?",
            ps -> { ps.setLong(1, tenant); ps.setString(2, name); },
            rs -> rs.getLong(1)).orElse(0L);
    }
}

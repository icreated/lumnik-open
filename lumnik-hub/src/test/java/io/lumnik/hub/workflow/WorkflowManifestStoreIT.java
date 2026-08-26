package io.lumnik.hub.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowManifestStoreIT {

    @Inject WorkflowManifestStore store;

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
        store.upsert(7001L, m, YAML);
        store.upsert(7001L, m, YAML); // idempotent upsert

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
}

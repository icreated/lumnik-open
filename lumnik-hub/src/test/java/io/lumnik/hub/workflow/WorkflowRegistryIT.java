package io.lumnik.hub.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowRegistryIT {

    @Inject WorkflowManifestStore store;
    @Inject WorkflowRegistry registry;

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-registry-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_registry_it, column: statut }
          states:
            - { code: A, initial: true, aliases: [debut] }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    @Test
    void registry_serves_graphs_from_the_store_per_tenant() {
        store.upsert(7101L, new WorkflowManifestParser().parse(YAML), YAML);

        var graphs = registry.all(7101L);
        assertThat(graphs).extracting(WorkflowGraph::name).contains("wf-registry-it");
        WorkflowGraph g = registry.findByName(7101L, "wf-registry-it").orElseThrow();
        assertThat(g.scope()).isEqualTo("demo");
        assertThat(g.provenance()).isEqualTo("declared");
        assertThat(g.initialState()).isEqualTo("A");
        assertThat(g.states()).hasSize(2);
        assertThat(g.states().get(0).aliases()).containsExactly("debut");
        assertThat(g.transitions()).hasSize(1);
        assertThat(registry.all(7102L)).noneMatch(x -> x.name().equals("wf-registry-it"));
        assertThat(registry.findByName(7102L, "wf-registry-it")).isEmpty();
    }
}

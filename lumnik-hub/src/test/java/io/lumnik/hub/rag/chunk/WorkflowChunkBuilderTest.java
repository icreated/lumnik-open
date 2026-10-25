package io.lumnik.hub.rag.chunk;

import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.hub.workflow.WorkflowGraph;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowChunkBuilderTest {

    @Test
    void chunk_carries_states_aliases_terminals_transitions_and_provenance() {
        WorkflowGraph g = new WorkflowGraph(
            "commande-lifecycle", "magasin", "connector.t_commandes_csv", "statut", "declared",
            null, null,
            List.of(
                new WorkflowGraph.StateDto("BROUILLON", null, List.of(), false),
                new WorkflowGraph.StateDto("EN_COURS", "En préparation", List.of("en cours", "ip"), false),
                new WorkflowGraph.StateDto("TERMINE", null, List.of("fini"), true)),
            "BROUILLON",
            List.of(new WorkflowGraph.TransitionDto(List.of("BROUILLON"), "EN_COURS"),
                    new WorkflowGraph.TransitionDto(List.of("EN_COURS"), "TERMINE")),
            null);

        List<ExtractedChunk> chunks = new WorkflowChunkBuilder().build(g);

        assertThat(chunks).hasSize(1);
        ExtractedChunk c = chunks.get(0);
        assertThat(c.type()).isEqualTo("workflow");
        assertThat(c.ref()).isEqualTo("workflow/commande-lifecycle");
        assertThat(c.content())
            .contains("commande-lifecycle")
            .contains("connector.t_commandes_csv").contains("statut")
            .contains("BROUILLON (initial)")
            .contains("EN_COURS").contains("alias: en cours, ip")
            .contains("TERMINE (terminal").contains("alias: fini")
            .contains("BROUILLON->EN_COURS")
            .contains("declared by the integrator");
        assertThat(c.metadata()).containsEntry("scope", "magasin")
            .containsEntry("provenance", "declared");
    }
}

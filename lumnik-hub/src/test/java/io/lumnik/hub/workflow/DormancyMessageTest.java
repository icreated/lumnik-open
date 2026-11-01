package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The dormancy decision's sentence (#1363): English by default, the workflow's own when declared. */
class DormancyMessageTest {

    private static WorkflowGraph graph(String message) {
        return new WorkflowGraph("door-wf", "cold-chain", "connector.t_doors", "door_state", "declared", "door_id",
            new WorkflowManifest.DormancyDef("1m", message, List.of()),
            List.of(new WorkflowGraph.StateDto("OPEN", "Open", List.of(), false)), "OPEN", List.of(), null, null);
    }

    private static WorkflowObservationStore.Observation observed(String state) {
        return new WorkflowObservationStore.Observation("DOOR-17", state,
            Instant.parse("2026-10-21T23:39:12Z"), Instant.parse("2026-10-20T08:00:00Z"), null);
    }

    @Test
    void the_default_says_observed_unmoved_never_stuck() {
        String text = DormancyMessage.render(graph(null), observed("OPEN"), Map.of());
        assertThat(text).isEqualTo(
            "DOOR-17: observed unmoved in \"OPEN\" since 2026-10-21 (first observed 2026-10-20) — declared threshold 1m in door-wf");
        assertThat(text).doesNotContain("stuck");
    }

    @Test
    void a_declared_message_speaks_the_workflows_language() {
        assertThat(DormancyMessage.render(graph("{doc_key} : porte « {state_label} » depuis le {since}, seuil {threshold}"),
                observed("OPEN"), Map.of()))
            .isEqualTo("DOOR-17 : porte « Open » depuis le 2026-10-21, seuil 1m");
    }

    /** A document can sit in a state the workflow never declared: its label is its code. */
    @Test
    void an_undeclared_state_labels_itself() {
        assertThat(DormancyMessage.render(graph("{state_label}"), observed("LOST"), Map.of())).isEqualTo("LOST");
    }

    /** #1588: a name the sentence does not reserve renders from the version's columns; a reserved one still wins. */
    @Test
    void a_cited_column_renders_from_the_version_and_a_reserved_name_wins() {
        assertThat(DormancyMessage.render(graph("{doc_key} ({laboratory}): {state}"), observed("OPEN"),
                Map.of("laboratory", "Cryobiology Lab", "state", "open ")))
            .isEqualTo("DOOR-17 (Cryobiology Lab): OPEN");
    }
}

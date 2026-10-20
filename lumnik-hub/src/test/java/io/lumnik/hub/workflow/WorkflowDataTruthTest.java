package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a degraded report may tell the integrator of a probe that could not run (#1315). */
class WorkflowDataTruthTest {

    private static final WorkflowGraph GRAPH = new WorkflowGraph("wf-truth", "demo",
        "connector.t_wf_truth", "statut", "declared", null, null,
        List.of(new WorkflowGraph.StateDto("A", null, List.of(), true)), "A", List.of());

    @Test
    void a_data_class_failure_names_its_sqlstate_and_withholds_the_value() {
        WorkflowDataTruth.Report r = WorkflowDataTruth.degraded(1L, GRAPH,
            new SQLException("invalid input syntax for type bigint: \"ACME-PAYROLL-42\"", "22P02"));

        assertThat(r.checkUnavailable()).isTrue();
        assertThat(r.unavailableReason()).contains("22P02").doesNotContain("ACME-PAYROLL-42");
    }

    @Test
    void a_structural_failure_keeps_its_sqlstate_and_its_words() {
        WorkflowDataTruth.Report r = WorkflowDataTruth.degraded(1L, GRAPH,
            new SQLException("column \"no_such_col\" does not exist", "42703"));

        assertThat(r.unavailableReason())
            .isEqualTo("SQLSTATE 42703: column \"no_such_col\" does not exist");
    }
}

package io.lumnik.hub.proposal;

import io.lumnik.hub.workflow.WorkflowDataTruth.Report;
import io.lumnik.hub.workflow.WorkflowGraph;
import io.lumnik.hub.workflow.WorkflowManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEvidenceTest {

    private static WorkflowGraph graph(String key, boolean dormancy) {
        return new WorkflowGraph("wf", "demo", "connector.t_commandes", "statut", "declared", key,
            dormancy ? new WorkflowManifest.DormancyDef("7d") : null, List.of(), "A", List.of());
    }

    private static Report report(boolean tableMissing, boolean keyColumnMissing, boolean unavailable, String reason) {
        return new Report(List.of(), List.of(), 0L, tableMissing, keyColumnMissing, null, unavailable, reason);
    }

    private static final Report READ = report(false, false, false, null);

    @Test
    void the_status_column_is_confirmed_when_the_query_read_it() {
        Optional<WorkflowEvidence.Conclusion> c = WorkflowEvidence.dataTruth("status-column-exists", graph("id", true), READ);

        assertThat(c).get().extracting(WorkflowEvidence.Conclusion::check).isEqualTo("confirmed");
        assertThat(c.get().evidence()).isEqualTo(new Dossier.Evidence("discovery", "workflow-data-truth",
            "connector.t_commandes.statut", "the confrontation query read the designated status column"));
    }

    @Test
    void the_status_column_is_refuted_only_by_sqlstate_42703_on_a_present_table() {
        Optional<WorkflowEvidence.Conclusion> c = WorkflowEvidence.dataTruth("status-column-exists", graph("id", true),
            report(false, false, true, "SQLSTATE 42703: column \"statut\" does not exist"));

        assertThat(c).get().extracting(WorkflowEvidence.Conclusion::check).isEqualTo("refuted");
        assertThat(c.get().evidence().observed()).doesNotContain("does not exist"); // structure, not the driver's text
    }

    @Test
    void the_key_column_is_confirmed_only_with_a_key_a_table_and_a_probe_that_ran() {
        assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph("id", true), READ))
            .get().extracting(WorkflowEvidence.Conclusion::check).isEqualTo("confirmed");
        assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph("id", true), READ).get().evidence().target())
            .isEqualTo("connector.t_commandes.id");
        assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph(null, true), READ)).isEmpty(); // no spec.on.key
    }

    @Test
    void the_key_column_is_refuted_when_the_table_is_there_and_the_key_is_not() {
        assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph("nope", true), report(false, true, false, null)))
            .get().extracting(WorkflowEvidence.Conclusion::check).isEqualTo("refuted");
    }

    /** Not finding is not proving absence: every unavailability leaves the check where it was. */
    @Test
    void nothing_else_concludes() {
        Report tableMissing = report(true, false, false, null);
        Report timeout = report(false, false, true, "SQLSTATE 57014: canceling statement due to statement timeout");
        Report revoked = report(false, false, true, "SQLSTATE 42501: permission denied for table t_commandes");
        for (Report r : List.of(tableMissing, timeout, revoked)) {
            assertThat(WorkflowEvidence.dataTruth("status-column-exists", graph("id", true), r)).isEmpty();
            assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph("id", true), r)).isEmpty();
        }
        // a bare checkUnavailable without the 42703 code is not a refutation either
        assertThat(WorkflowEvidence.dataTruth("status-column-exists", graph("id", true), report(false, false, true, null))).isEmpty();
        // a table that is missing never makes a column "missing", even with the flag set
        assertThat(WorkflowEvidence.dataTruth("key-column-exists", graph("id", true), report(true, true, false, null))).isEmpty();
        assertThat(WorkflowEvidence.dataTruth("anything-else", graph("id", true), READ)).isEmpty();
    }

    @Test
    void the_apply_door_confirms_a_stored_dormancy_and_never_refutes() {
        Optional<WorkflowEvidence.Conclusion> c = WorkflowEvidence.applyDoor("dormancy-target-accepted", graph("id", true));

        assertThat(c).get().extracting(WorkflowEvidence.Conclusion::check).isEqualTo("confirmed");
        assertThat(c.get().evidence()).isEqualTo(new Dossier.Evidence("discovery", "workflow-apply-door",
            "connector.t_commandes", "a stored workflow declares spec.dormancy on the designated table"));
        assertThat(WorkflowEvidence.applyDoor("dormancy-target-accepted", graph("id", false))).isEmpty();
        assertThat(WorkflowEvidence.applyDoor("something-else", graph("id", true))).isEmpty();
    }

    @Test
    void no_observed_text_carries_a_value() {
        for (Optional<WorkflowEvidence.Conclusion> c : List.of(
            WorkflowEvidence.dataTruth("status-column-exists", graph("id", true), READ),
            WorkflowEvidence.dataTruth("key-column-exists", graph("id", true), READ),
            WorkflowEvidence.applyDoor("dormancy-target-accepted", graph("id", true)))) {
            assertThat(c.get().evidence().observed()).doesNotContainPattern("\\d");
        }
    }
}

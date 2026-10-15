package io.lumnik.hub.proposal;

import io.lumnik.hub.workflow.WorkflowDataTruth;
import io.lumnik.hub.workflow.WorkflowGraph;

import java.util.Optional;

/**
 * A replayed data-truth report, or a stored workflow, turned into a conclusion — or into none
 * (spec 2026-10-04, section 2). Pure. A refutation exists only in the two contractual cases; every
 * other unavailability leaves the check where it was, because not finding is not proving absence.
 * {@code observed} states structure, never a value: the dossier travels outside the hub.
 */
final class WorkflowEvidence {

    record Conclusion(String check, Dossier.Evidence evidence) {}

    static final String VIA_DATA_TRUTH = "workflow-data-truth";
    static final String VIA_APPLY_DOOR = "workflow-apply-door";

    private WorkflowEvidence() {}

    static Optional<Conclusion> dataTruth(String needs, WorkflowGraph g, WorkflowDataTruth.Report r) {
        if (r.tableMissing()) return Optional.empty();
        return switch (needs) {
            case "status-column-exists" -> {
                String target = g.table() + "." + g.column();
                if (!r.checkUnavailable()) {
                    yield Optional.of(conclusion("confirmed", VIA_DATA_TRUTH, target,
                        "the confrontation query read the designated status column"));
                }
                boolean absentColumn = r.unavailableReason() != null && r.unavailableReason().contains("SQLSTATE 42703");
                yield absentColumn
                    ? Optional.of(conclusion("refuted", VIA_DATA_TRUTH, target,
                        "the confrontation query found no such column on the designated table"))
                    : Optional.empty();
            }
            case "key-column-exists" -> {
                if (g.key() == null) yield Optional.empty();
                String target = g.table() + "." + g.key();
                if (r.keyColumnMissing()) {
                    yield Optional.of(conclusion("refuted", VIA_DATA_TRUTH, target,
                        "the designated key column is missing from the designated table"));
                }
                yield r.checkUnavailable()
                    ? Optional.empty()
                    : Optional.of(conclusion("confirmed", VIA_DATA_TRUTH, target,
                        "the designated key column exists on the designated table"));
            }
            default -> Optional.empty();
        };
    }

    /** Confirms only: refusals are not kept, so there is nothing to refute from. */
    static Optional<Conclusion> applyDoor(String needs, WorkflowGraph g) {
        if (!needs.equals("dormancy-target-accepted") || g.dormancy() == null) return Optional.empty();
        return Optional.of(conclusion("confirmed", VIA_APPLY_DOOR, g.table(),
            "a stored workflow declares spec.dormancy on the designated table"));
    }

    private static Conclusion conclusion(String check, String via, String target, String observed) {
        return new Conclusion(check, new Dossier.Evidence("discovery", via, target, observed));
    }
}

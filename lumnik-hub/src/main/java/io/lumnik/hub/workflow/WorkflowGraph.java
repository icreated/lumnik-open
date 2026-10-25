package io.lumnik.hub.workflow;

import java.util.List;

/**
 * The served shape of a document lifecycle — built from a kind:Workflow manifest.
 *
 * <p><b>Invariant.</b> {@code table} and {@code column} ride into the SQL of
 * {@link WorkflowDataTruth#check} and of the dormancy sweep. They ride quoted, so the NAME is free;
 * what quoting cannot do is confine WHICH table is watched, and only the parser does that
 * (see {@code WorkflowManifestParser.requireWatchedColumn}). The canonical constructor accepts any
 * string, so a graph that reaches SQL MUST have come from {@link #from(WorkflowManifest)}, i.e. from
 * a parsed manifest. Minting one by hand is a test-only liberty.
 */
public record WorkflowGraph(
    String name, String scope, String table, String column, String provenance,
    String key, WorkflowManifest.DormancyDef dormancy,
    List<StateDto> states,
    String initialState,
    List<TransitionDto> transitions,
    WorkflowManifest.DueDef due        // nullable — a watched due date (#1448)
) {
    public record StateDto(String code, String label, List<String> aliases, boolean terminal) {}
    public record TransitionDto(List<String> from, String to) {}

    /** The declared label of {@code state}, or the code itself when it has none (or is undeclared). */
    public String labelOf(String state) {
        return states.stream().filter(s -> s.code().equals(state) && s.label() != null)
            .map(StateDto::label).findFirst().orElse(state);
    }

    public static WorkflowGraph from(WorkflowManifest m) {
        return new WorkflowGraph(
            m.name(), m.scope(), m.table(), m.column(), m.provenance(),
            m.key(), m.dormancy(),
            m.states().stream()
                .map(s -> new StateDto(s.code(), s.label(), s.aliases(), s.terminal()))
                .toList(),
            m.initialState(),
            m.transitions().stream()
                .map(t -> new TransitionDto(t.from(), t.to()))
                .toList(),
            m.due());
    }
}

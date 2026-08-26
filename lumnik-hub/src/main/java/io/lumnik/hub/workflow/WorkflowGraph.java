package io.lumnik.hub.workflow;

import java.util.List;

/**
 * The served shape of a document lifecycle — built from a kind:Workflow manifest.
 *
 * <p><b>Invariant.</b> {@code table} and {@code column} ride unquoted into the SQL of
 * {@link WorkflowDataTruth#check}, and the canonical constructor accepts any string — so a graph
 * that reaches SQL MUST have come from {@link #from(WorkflowManifest)}, i.e. from a manifest the
 * parser grammar-checked (TABLE_RX/COLUMN_RX). Minting a graph by hand is a test-only liberty.
 */
public record WorkflowGraph(
    String name, String scope, String table, String column, String provenance,
    String key, WorkflowManifest.DormancyDef dormancy,
    List<StateDto> states,
    String initialState,
    List<TransitionDto> transitions
) {
    public record StateDto(String code, String label, List<String> aliases, boolean terminal) {}
    public record TransitionDto(List<String> from, String to) {}

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
                .toList());
    }
}

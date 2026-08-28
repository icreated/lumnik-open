package io.lumnik.hub.rag.chunk;

import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.hub.workflow.WorkflowGraph;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a WorkflowGraph as one corpus chunk. The provenance is spelled out in the text so
 * the chat can cite HOW this lifecycle is known — a declared graph must never read as
 * observed fact, and vice versa.
 */
@ApplicationScoped
public class WorkflowChunkBuilder {

    public List<ExtractedChunk> build(WorkflowGraph g) {
        String states = g.states().stream().map(s -> {
            StringBuilder b = new StringBuilder(s.code());
            List<String> marks = new ArrayList<>();
            if (s.code().equals(g.initialState())) marks.add("initial");
            if (s.terminal()) marks.add("terminal");
            if (s.label() != null) marks.add("« " + s.label() + " »");
            if (!s.aliases().isEmpty()) marks.add("alias: " + String.join(", ", s.aliases()));
            if (!marks.isEmpty()) b.append(" (").append(String.join(", ", marks)).append(")");
            return b.toString();
        }).collect(Collectors.joining(", "));

        String transitions = g.transitions().stream()
            .flatMap(t -> t.from().stream().map(f -> f + "->" + t.to()))
            .collect(Collectors.joining(", "));

        String content = String.format(
            "Workflow %s — cycle de vie de la colonne %s de la table %s (%s by the integrator)."
                + " États: %s.%s",
            g.name(), g.column(), g.table(), g.provenance(), states,
            transitions.isEmpty() ? "" : " Transitions autorisées: " + transitions + ".");

        return List.of(new ExtractedChunk("workflow", "workflow", "workflow/" + g.name(), content,
            Map.of("scope", g.scope(), "provenance", g.provenance(), "table", g.table())));
    }
}

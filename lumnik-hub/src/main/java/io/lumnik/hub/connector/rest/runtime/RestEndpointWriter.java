package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.mapping.MappingEngine;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.UUID;

/**
 * ConnectorEngine bridge writer for {@code rest-generic} connectors.
 *
 * <p>Parses the hybrid {@code target} spec from the merged endpoint config and writes the batch via
 * {@link MultiTargetWriter} (a {@link MappingTarget.HybridJsonb} target) — the shared write path, no
 * write logic forked. An endpoint {@code mapping} (#1617) runs the hooks over each item's promoted columns, then the
 * columns land from the mapped row and {@code data} from the item, untouched.
 */
public class RestEndpointWriter implements ItemWriter<Map<String, Object>> {

    private final MultiTargetWriter writer;
    private final MappingEngine engine;
    private final ObjectMapper mapper;
    private long cellErrors;
    private final SequencedSet<ScriptVersion> scripts = new LinkedHashSet<>();

    public RestEndpointWriter(MultiTargetWriter writer, MappingEngine engine, ObjectMapper mapper) {
        this.writer = writer;
        this.engine = engine;
        this.mapper = mapper;
    }

    @Override
    public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext ctx) {
        if (items.isEmpty()) return new WriteOutcome.AllWritten(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> targetCfg = (Map<String, Object>) ctx.config().get("target");
        HybridJsonbSpec spec = parseHybridSpec(targetCfg);

        MappingTarget.HybridJsonb hybrid =
            new MappingTarget.HybridJsonb(spec.table(), spec.uniqueKeyPath(), spec.promote(), ctx.endpointId(),
                spec.uniqueKeyTransform());

        UUID runId = ctx.runId() != null ? ctx.runId() : UUID.randomUUID();
        String mappingId = "rest:" + ctx.connectorName();
        MappingSpec mapping = mappingOf(mappingId, ctx.config());
        if (mapping == null) {
            cellErrors += writer.writeHybrid(hybrid, items, null, ctx.tenantId(), runId, mappingId, "rest",
                ctx.connectorId());
            return new WriteOutcome.AllWritten(items.size());
        }
        // A carry outlives a chunk only through the csv reader's cursor; here it would restart unseen at each one.
        if (engine.carriesState(mapping.cell())) {
            throw new MappingValidationException("a hook that carries a value from one row to the next (fill-down) "
                + "does not run on a REST endpoint — each item holds its own values");
        }

        int[] unreadable = new int[items.size()];
        MappingState state = new MappingState();
        List<Map<String, Object>> mapped = engine.transformEach(mapping,
            HybridJsonbWriter.promoted(spec, items, i -> unreadable[i]++), state);
        List<Map<String, Object>> keptItems = new ArrayList<>(items.size());
        List<Map<String, Object>> keptCells = new ArrayList<>(items.size());
        List<Map<String, Object>> droppedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (mapped.get(i) == null) {
                droppedItems.add(items.get(i));
                continue;
            }
            keptItems.add(items.get(i));
            keptCells.add(mapped.get(i));
            cellErrors += unreadable[i];   // counted for what is written, as the engine counts a dead letter's
        }
        cellErrors += state.counters().cellErrors().get()
            + writer.writeHybrid(hybrid, keptItems, keptCells, ctx.tenantId(), runId, mappingId, "rest",
                ctx.connectorId());
        scripts.addAll(state.scripts());
        return MappingEngine.writeOutcome(items.size(), asItems(state.dropped(), droppedItems));
    }

    /**
     * Each drop as the item it came from: a dead letter keeps what the API sent, not its promoted columns. One drop per
     * dropped item, in item order — transformEach maps the items one by one and refuses a row made several.
     */
    private static List<MappingState.DroppedRow> asItems(List<MappingState.DroppedRow> drops,
                                                         List<Map<String, Object>> droppedItems) {
        if (drops.size() != droppedItems.size()) {
            throw new IllegalStateException("the mapping recorded " + drops.size() + " drops for "
                + droppedItems.size() + " dropped items");
        }
        List<MappingState.DroppedRow> out = new ArrayList<>(drops.size());
        for (int k = 0; k < drops.size(); k++) out.add(new MappingState.DroppedRow(droppedItems.get(k), drops.get(k).reason()));
        return out;
    }

    /** The endpoint's {@code mapping} as a spec over its promoted columns, or {@code null} without one. */
    MappingSpec mappingOf(String id, Map<String, Object> config) {
        if (!(config.get("mapping") instanceof Map<?, ?> block)) return null;
        return MappingSpec.fromBlock(id, null, "promoted columns of " + id, mapper.valueToTree(block));
    }

    /** Promoted values that did not read as their column's type: the row is written, the cell NULL (#1398). */
    @Override
    public long drainCellErrors() {
        long n = cellErrors;
        cellErrors = 0;
        return n;
    }

    /** The versions of the Kotlin scripts the mapping ran (#1430). */
    @Override
    public SequencedSet<ScriptVersion> drainScripts() {
        SequencedSet<ScriptVersion> ran = new LinkedHashSet<>(scripts);
        scripts.clear();
        return ran;
    }

    /** Parses the {@link HybridJsonbSpec} (table / unique key / promote) from the endpoint target config. */
    HybridJsonbSpec parseHybridSpec(Map<String, Object> t) {
        // The parse (and its DDL-identifier guard) lives on the spec itself — one parser,
        // every reader. This wrapper stays for the writer's existing call sites and tests.
        return HybridJsonbSpec.fromTargetConfig(t);
    }
}

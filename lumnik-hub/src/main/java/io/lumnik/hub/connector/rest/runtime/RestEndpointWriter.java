package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ConnectorEngine bridge writer for {@code rest-generic} connectors.
 *
 * <p>Parses the hybrid {@code target} spec from the merged endpoint config and writes the batch via
 * {@link MultiTargetWriter} (a {@link MappingTarget.HybridJsonb} target) — the shared write path, no
 * write logic forked.
 */
public class RestEndpointWriter implements ItemWriter<Map<String, Object>> {

    private final MultiTargetWriter writer;
    private long cellErrors;

    public RestEndpointWriter(MultiTargetWriter writer) {
        this.writer = writer;
    }

    @Override
    public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext ctx) {
        if (items.isEmpty()) return new WriteOutcome.AllWritten(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> targetCfg = (Map<String, Object>) ctx.config().get("target");
        HybridJsonbSpec spec = parseHybridSpec(targetCfg);

        MappingTarget target = new MappingTarget(
            null, null, null,
            new MappingTarget.HybridJsonb(spec.table(), spec.uniqueKeyPath(), spec.promote(), ctx.endpointId()));

        UUID runId = ctx.runId() != null ? ctx.runId() : UUID.randomUUID();
        cellErrors += writer.write(target, items, ctx.tenantId(), runId,
            "rest:" + ctx.connectorName(), 1, "rest", ctx.connectorId(), List.of());

        return new WriteOutcome.AllWritten(items.size());
    }

    /** Promoted values that did not read as their column's type: the row is written, the cell NULL (#1398). */
    @Override
    public long drainCellErrors() {
        long n = cellErrors;
        cellErrors = 0;
        return n;
    }

    /** Parses the {@link HybridJsonbSpec} (table / unique key / promote) from the endpoint target config. */
    HybridJsonbSpec parseHybridSpec(Map<String, Object> t) {
        // The parse (and its DDL-identifier guard) lives on the spec itself — one parser,
        // every reader. This wrapper stays for the writer's existing call sites and tests.
        return HybridJsonbSpec.fromTargetConfig(t);
    }
}

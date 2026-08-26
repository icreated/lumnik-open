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
        writer.write(target, items, ctx.tenantId(), runId,
            "rest:" + ctx.connectorName(), 1, "rest", ctx.connectorId(), List.of());

        return new WriteOutcome.AllWritten(items.size());
    }

    /** Parses the {@link HybridJsonbSpec} (table / unique key / promote) from the endpoint target config. */
    @SuppressWarnings("unchecked")
    HybridJsonbSpec parseHybridSpec(Map<String, Object> t) {
        if (t == null) throw new IllegalArgumentException("endpoint config missing 'target'");
        String table = (String) t.get("table");
        // DDL-injection boundary (layer 2, alongside the escaping fix in RlsDdl): `table` is read
        // verbatim from the manifest and handed straight to HybridJsonbWriter's DDL, executed on the
        // BYPASSRLS admin datasource — this path bypasses MappingLoader.parseTable entirely, so it
        // must not trust its caller either.
        if (!SqlIdentifiers.isPlainQualifiedIdentifier(table)) {
            throw new IllegalArgumentException(
                "target.table must be a plain identifier or schema.table pair (letters, digits, underscore; "
                    + "not starting with a digit): " + table);
        }
        String key   = (String) t.get("unique_key");

        List<Map<String, Object>> rawPromote =
            (List<Map<String, Object>>) t.getOrDefault("promote", List.of());

        List<PromotedColumn> promote = new ArrayList<>();
        for (Map<String, Object> p : rawPromote) {
            String typeStr = String.valueOf(p.get("type")).toUpperCase();
            Object transformRaw = p.get("transform");
            Optional<Transform> transform = transformRaw != null
                ? Optional.of(Transform.valueOf(String.valueOf(transformRaw).toUpperCase()))
                : Optional.empty();
            promote.add(new PromotedColumn(
                (String) p.get("name"),
                (String) p.get("path"),
                SqlType.valueOf(typeStr),
                transform));
        }
        return new HybridJsonbSpec(table, key, promote);
    }
}

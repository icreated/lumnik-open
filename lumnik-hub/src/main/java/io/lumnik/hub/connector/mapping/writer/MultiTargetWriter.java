package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MultiTargetWriter {

  @Inject RagWriter ragWriter;
  @Inject DynamicTableWriter tableWriter;
  @Inject HybridJsonbWriter hybridJsonbWriter;
  @Inject RowHasher hasher;

  /**
   * Writes rows to all configured targets (rag, table, jpa, hybridJsonb) in the current transaction.
   *
   * <p>JPA writer is not implemented in v1 — it's opt-in late and reserved for Ionic apps phase.
   * <p>hybridJsonb target is constructed directly by RestEndpointWriter from endpoint config
   * (stored as JSONB on connector_endpoint.config); it does not go through the YAML mapping loader.
   */
  public long write(MappingTarget target, List<Map<String, Object>> rows, Long tenantId, UUID runId,
                    String mappingId, int mappingVersion, String source,
                    UUID connectorId, List<String> tags) {
    return write(target, rows, tenantId, runId, mappingId, mappingVersion, source, connectorId, tags, Map.of());
  }

  /** @param sourceTypeHints source-declared column types, forwarded to the dynamic table writer. */
  public long write(MappingTarget target, List<Map<String, Object>> rows, Long tenantId, UUID runId,
                    String mappingId, int mappingVersion, String source,
                    UUID connectorId, List<String> tags, Map<String, String> sourceTypeHints) {
    return write(target, rows, tenantId, runId, mappingId, mappingVersion, source, connectorId, tags,
        sourceTypeHints, null);
  }

  /**
   * @param sourceTypeHints  source-declared column types, forwarded to the dynamic table writer.
   * @param preparedVectors  row_hash → vector, computed ahead of this transaction by
   *                         {@link #prepare}; {@code null} means no prepare phase ran, so the RAG
   *                         target embeds inline — the original, backward-compatible behaviour.
   * @return the hybrid target's promoted cells that did not read as their column's type (#1398); 0 elsewhere
   */
  public long write(MappingTarget target, List<Map<String, Object>> rows, Long tenantId, UUID runId,
                    String mappingId, int mappingVersion, String source,
                    UUID connectorId, List<String> tags, Map<String, String> sourceTypeHints,
                    Map<String, float[]> preparedVectors) {
    if (rows.isEmpty()) return 0;
    if (target == null) return 0;

    List<Map<String, Object>> systemContexts =
        buildSystemContexts(rows, tenantId, runId, mappingId, mappingVersion, source);

    // RAG (always if configured) — batched: one dedup probe, sub-batched embedAll, one INSERT batch
    if (target.rag() != null) {
      ragWriter.writeAll(target.rag(), rows, systemContexts, tenantId, connectorId, tags, preparedVectors);
    }

    // Dynamic table (default if configured)
    if (target.table() != null) {
      tableWriter.write(target.table(), rows, systemContexts, tenantId, connectorId, sourceTypeHints);
    }

    // Hybrid JSONB target
    if (target.hybridJsonb() != null) {
      return writeHybrid(target.hybridJsonb(), rows, null, systemContexts, tenantId, connectorId);
    }
    return 0;
  }

  /**
   * A hybrid write whose promoted columns a mapping computed (#1617): {@code cells.get(i)} holds item {@code i}'s;
   * {@code null} reads each column by its path. The row identity is the item's, as in {@link #write}: what a mapping
   * computes is compared column by column.
   *
   * @return the promoted cells that did not read as their column's type (#1398)
   */
  public long writeHybrid(MappingTarget.HybridJsonb hj, List<Map<String, Object>> items,
                          List<Map<String, Object>> cells, Long tenantId, UUID runId, String mappingId,
                          String source, UUID connectorId) {
    if (items.isEmpty()) return 0;
    return writeHybrid(hj, items, cells, buildSystemContexts(items, tenantId, runId, mappingId, 1, source), tenantId,
        connectorId);
  }

  private long writeHybrid(MappingTarget.HybridJsonb hj, List<Map<String, Object>> items,
                           List<Map<String, Object>> cells, List<Map<String, Object>> systemContexts,
                           Long tenantId, UUID connectorId) {
    HybridJsonbSpec spec = new HybridJsonbSpec(hj.table(), hj.uniqueKeyPath(), hj.promote(), hj.uniqueKeyTransform());
    return hybridJsonbWriter.write(spec, items, cells, systemContexts, tenantId, connectorId, hj.endpointId());
  }

  /**
   * Prepare phase (the {@code ItemWriter.prepare} hook's writer-side counterpart): renders and
   * embeds the RAG target's chunks ahead of the write transaction. Pure delegation — the actual
   * rendering/embedding/caching logic lives in {@link RagWriter#prepare}; this only builds the
   * SAME system-context shape {@link #write} builds (row_hash included, so a row's hash is
   * identical whichever phase computes it) and skips targets that have nothing to prepare: table
   * and hybridJsonb writes are pure JDBC and stay entirely inside the write transaction.
   *
   * @return row_hash → vector for {@link #write} to consume via its {@code preparedVectors}
   *         parameter; {@code Map.of()} when there is no RAG target or no rows.
   */
  public Map<String, float[]> prepare(MappingTarget target, List<Map<String, Object>> rows, Long tenantId,
                                      UUID runId, String mappingId, int mappingVersion, String source) {
    if (rows.isEmpty() || target == null || target.rag() == null) return Map.of();

    List<Map<String, Object>> systemContexts =
        buildSystemContexts(rows, tenantId, runId, mappingId, mappingVersion, source);
    return ragWriter.prepare(target.rag(), rows, systemContexts, tenantId);
  }

  /** Per-row system context shared by {@link #write} and {@link #prepare} — same shape, so a
   * row's {@code _row_hash} (the prepare-cache key) agrees between the two phases. */
  private List<Map<String, Object>> buildSystemContexts(List<Map<String, Object>> rows, Long tenantId,
                                                         UUID runId, String mappingId, int mappingVersion,
                                                         String source) {
    List<Map<String, Object>> systemContexts = new ArrayList<>(rows.size());
    for (var row : rows) {
      String hash = hasher.hash(row);
      Map<String, Object> sys = new HashMap<>();
      sys.put("_row_hash", hash);
      sys.put("_tenant", tenantId);
      sys.put("_run_id", runId);
      sys.put("_mapping", mappingId);
      sys.put("_mapping_version", mappingVersion);
      sys.put("_source", source);
      sys.put("_now", Instant.now().toString());
      systemContexts.add(sys);
    }
    return systemContexts;
  }
}

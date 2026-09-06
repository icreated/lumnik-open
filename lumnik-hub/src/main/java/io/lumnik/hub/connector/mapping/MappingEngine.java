package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Executes a MappingSpec pipeline: Dataset hooks (one-shot) → Row hooks (streaming) → Cell hooks (per cell).
 *
 * <h2>Usage from a ConnectorType</h2>
 * <pre>{@code
 * @ApplicationScoped
 * @ConnectorTypeId("my-source")
 * public class MyConnectorType implements ConnectorType {
 *   @Inject MappingEngine mappingEngine;
 *   @Inject MappingResolver mappingResolver;  // available after Task 15
 *
 *   @Override
 *   public ChunkResult process(Connector c, ChunkContext ctx, List<RawRow> chunk, boolean endOfStream) {
 *     if (c.getMappingId() != null) {
 *       MappingSpec spec = mappingResolver.resolve(c.getMappingId(), c.getTenantId());
 *       MappingState state = ctx.mappingState();  // request-scoped, persists across chunks of same run
 *       var transformed = mappingEngine.transform(spec, chunk, state, endOfStream);
 *       return writer.write(transformed);
 *     }
 *     // Legacy path: bare ItemProcessor (foundation behavior unchanged)
 *     return defaultProcessing(chunk);
 *   }
 * }
 * }</pre>
 *
 * <p>End-of-stream flush gives aggregator hooks a chance to emit their last buffered group.
 *
 * <p>Thread-safety: {@code transformAndWrite} builds a fresh {@link MappingState} on every call,
 * and every call is one chunk, not one run — see {@link MappingState}'s javadoc for the
 * consequence.
 */
@ApplicationScoped
public class MappingEngine {

  @Inject HookCatalog catalog;
  @Inject MultiTargetWriter multiWriter;

  /**
   * Transform a chunk of rows through the mapping pipeline.
   *
   * @param spec        validated mapping
   * @param input       chunk rows (mutated in-place by cell hooks)
   * @param state       cross-chunk state (carries aggregator buffers + counters)
   * @param endOfStream true if this is the last chunk; flush aggregator buffers
   */
  public List<Map<String, Object>> transform(
      MappingSpec spec,
      List<Map<String, Object>> input,
      MappingState state,
      boolean endOfStream) {

    validateHookParams(spec);

    List<Map<String, Object>> rows = new ArrayList<>(input);
    state.counters().rowsIn().addAndGet(rows.size());

    rows = applyDatasetHooks(spec, rows, state);
    rows = applyRowHooks(spec, rows, state);
    if (endOfStream) flushRowHooks(spec, rows, state);
    applyCellHooks(spec, rows, state);

    state.counters().rowsOut().addAndGet(rows.size());
    return rows;
  }

  /**
   * Resolve and validate every row/cell hook ONCE, before any row. Params are invariant across the
   * chunk, so a param-shape refusal that hooks used to run per row — and that slipped through
   * entirely on an empty chunk, since {@code apply()} never fired — now fires here, exactly once.
   * An UNKNOWN hook is refused here too, for the same reason: the apply tiers name it, but they name
   * it inside the per-row loop, which an empty chunk skips. (Dataset hooks need no line here — their
   * apply tier iterates invocations, not rows, so it already refuses an unknown one on an empty chunk.)
   */
  private void validateHookParams(MappingSpec spec) {
    for (HookInvocation inv : spec.row()) {
      RowHook hook = catalog.row(inv.name());
      if (hook == null) throw new MappingValidationException("unknown row hook: " + inv.name());
      hook.validateParams(inv.params());
    }
    for (HookInvocation inv : spec.cell()) {
      CellHook hook = catalog.cell(inv.name());
      if (hook == null) throw new MappingValidationException("unknown cell hook: " + inv.name());
      hook.validateParams(inv.params());
    }
  }

  /** Tier 1: dataset hooks (one-shot, may rewrite the whole chunk). */
  private List<Map<String, Object>> applyDatasetHooks(
      MappingSpec spec, List<Map<String, Object>> rows, MappingState state) {
    for (HookInvocation inv : spec.dataset()) {
      DatasetHook hook = catalog.dataset(inv.name());
      if (hook == null) {
        throw new MappingValidationException("unknown dataset hook: " + inv.name());
      }
      rows = hook.apply(rows, inv.params(), state);
    }
    return rows;
  }

  /** Tier 2: row hooks (streaming per row; split-row emits N, aggregator may emit 0 until flush). */
  private List<Map<String, Object>> applyRowHooks(
      MappingSpec spec, List<Map<String, Object>> rows, MappingState state) {
    List<Map<String, Object>> postRow = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      postRow.addAll(runRowChain(spec, row, state));
    }
    return postRow;
  }

  /**
   * Run the full row-hook chain over a single row, fanning out as hooks emit.
   *
   * <p>Defensive net (anti-phantom-protection): a dropping hook MUST record why in
   * {@code state.dropped()} — see {@code ValidateRowHook}. If the chain instead emits nothing
   * for this row and nothing was recorded, that is a future hook bug reintroducing the silent-drop
   * defect this task fixes; record a {@code MappingFailed} so the row is never simply lost.
   * Per-culprit, not per-spec: stood down only when the hook that actually emptied the chain
   * ({@code lastHook}) is the {@code aggregator} — buffering fan-in legitimately emits 0 rows per
   * input until its key changes (or {@code flush} at end-of-stream), which is not a drop. Any
   * OTHER hook emptying the chain still trips the net, whether or not an aggregator sits
   * elsewhere in the spec: the aggregator's mere presence must never mask a different hook's bug.
   */
  private List<Map<String, Object>> runRowChain(
      MappingSpec spec, Map<String, Object> row, MappingState state) {
    List<Map<String, Object>> current = List.of(row);
    int droppedBefore = state.dropped().size();
    String lastHook = null;
    for (HookInvocation inv : spec.row()) {
      RowHook hook = catalog.row(inv.name());
      if (hook == null) {
        throw new MappingValidationException("unknown row hook: " + inv.name());
      }
      List<Map<String, Object>> next = new ArrayList<>();
      for (Map<String, Object> r : current) {
        next.addAll(hook.apply(r, inv.params(), state));
      }
      current = next;
      lastHook = inv.name();
      if (current.isEmpty()) break;
    }
    boolean emptiedByAggregator = "aggregator".equals(lastHook);
    if (current.isEmpty() && !emptiedByAggregator && state.dropped().size() == droppedBefore) {
      state.dropped().add(new MappingState.DroppedRow(new HashMap<>(row),
          new SkipReason.MappingFailed(lastHook, "hook emitted no rows without declaring a drop")));
    }
    return current;
  }

  /** End-of-stream flush — gives aggregators a chance to emit the last buffered group. */
  private void flushRowHooks(MappingSpec spec, List<Map<String, Object>> rows, MappingState state) {
    for (HookInvocation inv : spec.row()) {
      RowHook hook = catalog.row(inv.name());
      if (hook != null) {
        rows.addAll(hook.flush(inv.params(), state));
      }
    }
  }

  /** Tier 3: cell hooks (per cell of each row, mutates in place). */
  private void applyCellHooks(MappingSpec spec, List<Map<String, Object>> rows, MappingState state) {
    for (Map<String, Object> row : rows) {
      for (HookInvocation inv : spec.cell()) {
        CellHook hook = catalog.cell(inv.name());
        if (hook == null) {
          throw new MappingValidationException("unknown cell hook: " + inv.name());
        }
        hook.apply(row, inv.params(), state);
      }
    }
  }

  /**
   * Transform a chunk and write to all configured targets in one logical step.
   * The current transaction (managed by caller) holds the writes together.
   */
  public MappingOutcome transformAndWrite(MappingSpec spec, List<Map<String, Object>> input,
                                Long tenantId, UUID runId, boolean endOfStream,
                                UUID connectorId, List<String> tags) {
    return transformAndWrite(spec, input, tenantId, runId, endOfStream, connectorId, tags, Map.of());
  }

  /** @param sourceTypeHints source-declared column types, forwarded to the writer for DDL typing. */
  public MappingOutcome transformAndWrite(MappingSpec spec, List<Map<String, Object>> input,
                                Long tenantId, UUID runId, boolean endOfStream,
                                UUID connectorId, List<String> tags,
                                Map<String, String> sourceTypeHints) {
    return transformAndWrite(spec, input, tenantId, runId, endOfStream, connectorId, tags, sourceTypeHints, null);
  }

  /**
   * @param sourceTypeHints source-declared column types, forwarded to the writer for DDL typing.
   * @param preparedVectors row_hash → vector computed ahead of this transaction by
   *                        {@link #prepareEmbeddings}; {@code null} means no prepare phase ran,
   *                        so the RAG target embeds inline — the original, unchanged behaviour.
   * @return the rows this transform dropped (empty when every source row was written) — the
   *         mapping tier's honest count, source of {@code WriteOutcome.PartialWrite} — paired
   *         with how many cells the pipeline's cell hooks nulled because they could not parse
   *         the value; see {@link MappingOutcome}.
   * @throws MappingValidationException if more drops were recorded than source rows came in —
   *         belt-and-braces against a hook composition the validator doesn't refuse (e.g. a
   *         custom/future dropping hook placed after {@code split-row}) that would otherwise
   *         drop fanned fragments individually and corrupt {@code records_out} into a negative
   *         count. Refusing the run beats silently persisting the lie this task exists to fix.
   */
  public MappingOutcome transformAndWrite(MappingSpec spec, List<Map<String, Object>> input,
                                Long tenantId, UUID runId, boolean endOfStream,
                                UUID connectorId, List<String> tags,
                                Map<String, String> sourceTypeHints,
                                Map<String, float[]> preparedVectors) {
    MappingState state = new MappingState();
    var transformed = transform(spec, input, state, endOfStream);
    if (state.dropped().size() > input.size()) {
      throw new MappingValidationException(
          "mapping recorded " + state.dropped().size() + " drops for " + input.size()
              + " source rows — a hook dropped fanned fragments individually (e.g. validate-row "
              + "after split-row), which would corrupt records_out into a negative count; "
              + "refusing to write");
    }
    multiWriter.write(spec.targets(), transformed, tenantId, runId,
        spec.id(), spec.version(), spec.id(), connectorId, tags, sourceTypeHints, preparedVectors);
    return new MappingOutcome(state.dropped(), state.counters().cellErrors().get());
  }

  /**
   * Prepare-phase counterpart to {@link #transformAndWrite}, meant to be called OUTSIDE the
   * write transaction (the {@code ItemWriter.prepare} hook). Runs the SAME transform — fresh
   * {@link MappingState}, {@code endOfStream=false} (matching every call site's own
   * {@code transformAndWrite} call, so the two passes agree on the row set) — purely to render
   * the RAG target's chunk texts and embed them ahead of time. CPU-only re-work plus the one
   * network call ({@code embedAll}); no JDBC, no tenant-scoped connection.
   *
   * @return a {@link PrepareOutcome} carrying row_hash → vector (empty when there is no RAG
   *         target) alongside how many cells THIS pass's cell hooks nulled — see
   *         {@link PrepareOutcome}'s javadoc for why that count, not the write pass's own, is the
   *         one that must reach the run, and why this method deliberately mutates {@code input}
   *         in place rather than working on a copy.
   */
  public PrepareOutcome prepareEmbeddings(MappingSpec spec, List<Map<String, Object>> input,
                                                Long tenantId, UUID runId,
                                                UUID connectorId, List<String> tags) {
    // Deliberately mutates the CALLER'S row maps in place — the SAME maps
    // ChunkExecutor.executeChunk hands to `write(...)` right afterwards — exactly like
    // transformAndWrite does. This is NOT an oversight: `to-uuid`'s `generate: true` and
    // `enrich-context`'s `${now}` are content-nondeterministic across separate transform passes,
    // and RagWriter.useCachedVectors looks its prepared vector up by the TRANSFORMED row's hash
    // (MultiTargetWriter.java buildSystemContexts). If this pass worked on a copy, it would
    // generate a DIFFERENT uuid/timestamp into a DIFFERENT object than the write pass does right
    // after, the two hashes would disagree, and the prepared vector would silently miss — the
    // chunk vanishes from RAG with only a WARN log. Sharing the mutation means the value THIS
    // pass generates is still sitting in the row when the write pass runs over the same map, so
    // the two hashes agree. See PrepareOutcome for how the cell-error count is still made honest
    // without copying: this pass reports its OWN count, which the caller sums with the write
    // pass's (necessarily zero for the same cells — every cell hook starts
    // `if (v == null) return;`).
    MappingState state = new MappingState();
    var transformed = transform(spec, input, state, false);
    Map<String, float[]> vectors = multiWriter.prepare(spec.targets(), transformed, tenantId, runId,
        spec.id(), spec.version(), spec.id());
    return new PrepareOutcome(vectors, state.counters().cellErrors().get());
  }

  /**
   * Wraps a {@link MappingSpec} into a {@code ChunkExecutor}-ready {@link ItemWriter} — the ONE
   * seam every MappingSpec-driven {@code ConnectorType} (csv-file, jdbc-generic, kafka) should
   * build its writer through, instead of a bare write-only lambda. A lambda can only implement
   * {@code ItemWriter}'s single abstract method, so it can never reach the {@code prepare} hook —
   * whatever RAG target a mapping declares would keep embedding inside the write transaction
   * regardless of how many call sites exist. This factory is the fix at the root, not per-type.
   */
  public ItemWriter<Map<String, Object>> itemWriter(MappingSpec spec) {
    return itemWriter(spec, ctx -> Map.of());
  }

  /** @param sourceTypeHints per-chunk source-declared column types (jdbc-generic; csv-file has none). */
  public ItemWriter<Map<String, Object>> itemWriter(MappingSpec spec,
      Function<ConnectorContext, Map<String, String>> sourceTypeHints) {
    return new MappingSpecItemWriter(this, spec, sourceTypeHints);
  }

  /**
   * Per-chunk bridge between {@code ItemWriter}'s two-phase lifecycle and
   * {@code transformAndWrite}/{@code prepareEmbeddings}. {@code ConnectorType.writer(ctx)} is
   * called once per chunk by {@code ChunkExecutor} (a fresh {@code Prepared} step per
   * {@code executeChunk}), so a fresh instance of this class is built every chunk — its
   * {@code preparedVectors} and {@code cellErrors} fields cannot leak across chunks or tenants:
   * there is no bean-scoped storage for a later chunk to read, the fields die with this object at
   * the end of the chunk. {@code cellErrors} is additionally drained (zeroed) by
   * {@link #drainCellErrors}, mirroring {@code ItemReader.drainMalformed()} on the reader side.
   *
   * <p>{@code cellErrors} is counted from TWO places summed together: {@code rehearsalCellErrors}
   * (this chunk's {@code prepare()} pass, if it ran) plus {@code write()}'s own
   * {@code outcome.cellErrors()}. This is not double-counting — see {@link PrepareOutcome}: when
   * {@code prepare()} ran, it shared-mutated the same row maps {@code write()} transforms again,
   * so every nulled cell is already {@code null} by the time {@code write()}'s own cell hooks see
   * it and their {@code if (v == null) return;} guard contributes 0 for it — the sum is exactly
   * the rehearsal's count. When {@code prepare()} never ran (a writer used outside
   * {@code ChunkExecutor}, or a mapping with no RAG target skipping straight past the rehearsal),
   * {@code rehearsalCellErrors} stays at its initial 0 and the sum is {@code write()}'s own count.
   */
  private static final class MappingSpecItemWriter implements ItemWriter<Map<String, Object>> {
    private final MappingEngine engine;
    private final MappingSpec spec;
    private final Function<ConnectorContext, Map<String, String>> sourceTypeHints;
    private Map<String, float[]> preparedVectors = Map.of();
    private long rehearsalCellErrors;
    private long cellErrors;

    MappingSpecItemWriter(MappingEngine engine, MappingSpec spec,
        Function<ConnectorContext, Map<String, String>> sourceTypeHints) {
      this.engine = engine;
      this.spec = spec;
      this.sourceTypeHints = sourceTypeHints;
    }

    @Override
    public void prepare(List<Map<String, Object>> records, ConnectorContext ctx) {
      PrepareOutcome outcome = engine.prepareEmbeddings(spec, records, ctx.tenantId(), ctx.runId(),
          ctx.connectorId(), List.of());
      preparedVectors = outcome.preparedVectors();
      rehearsalCellErrors = outcome.cellErrors();
    }

    @Override
    public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext ctx) {
      var outcome = engine.transformAndWrite(spec, items, ctx.tenantId(), ctx.runId(), /* endOfStream */ false,
          ctx.connectorId(), List.of(), sourceTypeHints.apply(ctx), preparedVectors);
      cellErrors += rehearsalCellErrors + outcome.cellErrors();
      rehearsalCellErrors = 0;
      var drops = outcome.dropped();
      if (drops.isEmpty()) {
        return new WriteOutcome.AllWritten(items.size());
      }
      return new WriteOutcome.PartialWrite(items.size() - drops.size(),
          drops.stream().map(d -> new WriteOutcome.SkippedRecord(d.row(), d.reason())).toList());
    }

    @Override
    public long drainCellErrors() {
      long n = cellErrors;
      cellErrors = 0;
      return n;
    }
  }
}

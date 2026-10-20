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
 *
 *   @Override
 *   public ItemWriter<?> writer(ConnectorContext ctx) {
 *     MappingSpec spec = buildSpec(ctx.config());  // the type builds its spec from its own config
 *     return mappingEngine.itemWriter(spec);
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

  /**
   * @param sourceTypeHints source-declared column types, forwarded to the writer for DDL typing.
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
                                Map<String, String> sourceTypeHints) {
    MappingState state = new MappingState();
    var transformed = transform(spec, input, state, endOfStream);
    return write(spec, input.size(), transformed, state, tenantId, runId, connectorId, tags,
        sourceTypeHints, null);
  }

  /**
   * The {@code ItemWriter.prepare} half of a chunk, called OUTSIDE the write transaction: runs the
   * chunk's one transform — fresh {@link MappingState}, {@code endOfStream=false} like every
   * {@link #itemWriter} write — and, when the spec has a RAG target, renders and embeds the chunk
   * texts ahead of time: the one network call ({@code embedAll}), no JDBC, no tenant-scoped
   * connection. {@link #writePrepared} then writes these rows without transforming them again;
   * {@link PreparedChunk} says why the chunk is never transformed twice.
   */
  public PreparedChunk prepare(MappingSpec spec, List<Map<String, Object>> input, Long tenantId, UUID runId) {
    MappingState state = new MappingState();
    var transformed = transform(spec, input, state, false);
    Map<String, float[]> vectors = multiWriter.prepare(spec.targets(), transformed, tenantId, runId,
        spec.id(), spec.version(), spec.id());
    return new PreparedChunk(input.size(), transformed, state, vectors);
  }

  /** Writes a {@link #prepare}d chunk in the caller's transaction: {@link #transformAndWrite}'s
   *  outcome and refusal, without its transform. */
  public MappingOutcome writePrepared(MappingSpec spec, PreparedChunk chunk, Long tenantId, UUID runId,
                                      UUID connectorId, List<String> tags,
                                      Map<String, String> sourceTypeHints) {
    return write(spec, chunk.sourceRows(), chunk.rows(), chunk.state(), tenantId, runId, connectorId,
        tags, sourceTypeHints, chunk.vectors());
  }

  /** @param preparedVectors row_hash → vector embedded by {@link #prepare}; {@code null} embeds inline. */
  private MappingOutcome write(MappingSpec spec, int sourceRows, List<Map<String, Object>> transformed,
                               MappingState state, Long tenantId, UUID runId, UUID connectorId,
                               List<String> tags, Map<String, String> sourceTypeHints,
                               Map<String, float[]> preparedVectors) {
    if (state.dropped().size() > sourceRows) {
      throw new MappingValidationException(
          "mapping recorded " + state.dropped().size() + " drops for " + sourceRows
              + " source rows — a hook dropped fanned fragments individually (e.g. validate-row "
              + "after split-row), which would corrupt records_out into a negative count; "
              + "refusing to write");
    }
    multiWriter.write(spec.targets(), transformed, tenantId, runId,
        spec.id(), spec.version(), spec.id(), connectorId, tags, sourceTypeHints, preparedVectors);
    return new MappingOutcome(state.dropped(), state.counters().cellErrors().get());
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
   * {@link #prepare}/{@link #writePrepared}/{@link #transformAndWrite}. {@code ConnectorType.writer(ctx)}
   * is called once per chunk by {@code ChunkExecutor} (a fresh {@code Prepared} step per
   * {@code executeChunk}), so a fresh instance of this class is built every chunk — its
   * {@code prepared} and {@code cellErrors} fields cannot leak across chunks or tenants: there is
   * no bean-scoped storage for a later chunk to read, the fields die with this object at the end
   * of the chunk. {@code cellErrors} is additionally drained (zeroed) by {@link #drainCellErrors},
   * mirroring {@code ItemReader.drainMalformed()} on the reader side.
   *
   * <p>When {@code prepare()} ran, {@code write()} lands the chunk it prepared — and refuses any
   * other list, whose rows it would otherwise not be landing. When it did not (a writer used
   * outside {@code ChunkExecutor}), {@code write()} transforms and writes in one step. Either way
   * the chunk is transformed exactly once, so that transform's cell-error count is the chunk's.
   */
  private static final class MappingSpecItemWriter implements ItemWriter<Map<String, Object>> {
    private final MappingEngine engine;
    private final MappingSpec spec;
    private final Function<ConnectorContext, Map<String, String>> sourceTypeHints;
    private List<Map<String, Object>> preparedFor;
    private PreparedChunk prepared;
    private long cellErrors;

    MappingSpecItemWriter(MappingEngine engine, MappingSpec spec,
        Function<ConnectorContext, Map<String, String>> sourceTypeHints) {
      this.engine = engine;
      this.spec = spec;
      this.sourceTypeHints = sourceTypeHints;
    }

    @Override
    public void prepare(List<Map<String, Object>> records, ConnectorContext ctx) {
      prepared = engine.prepare(spec, records, ctx.tenantId(), ctx.runId());
      preparedFor = records;
    }

    @Override
    public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext ctx) {
      MappingOutcome outcome;
      if (prepared == null) {
        outcome = engine.transformAndWrite(spec, items, ctx.tenantId(), ctx.runId(), /* endOfStream */ false,
            ctx.connectorId(), List.of(), sourceTypeHints.apply(ctx));
      } else {
        if (items != preparedFor) {
          throw new IllegalStateException("write() must be handed the records prepare() was given");
        }
        outcome = engine.writePrepared(spec, prepared, ctx.tenantId(), ctx.runId(), ctx.connectorId(),
            List.of(), sourceTypeHints.apply(ctx));
        prepared = null;
        preparedFor = null;
      }
      cellErrors += outcome.cellErrors();
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

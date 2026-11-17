package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.hub.connector.api.types.SkipReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * State for one {@code MappingEngine.transform} pass. Holds aggregator buffers (per stateKey) +
 * atomic counters + the rows dropped by hooks.
 *
 * <p>On the production path ({@code MappingEngine.transformAndWrite} /
 * {@code prepare}) this is built fresh on <b>every call, and every call is one chunk</b>
 * — despite the name, it does not live for the whole run. Consequence: hook state does not
 * survive a chunk boundary, so a hook that needs to buffer across chunks (e.g. {@code aggregator})
 * cannot rely on this class to carry that buffer — see {@code CsvFileConnectorValidator}'s
 * {@code aggregatorRefused}, which refuses the {@code aggregator} row hook on csv-file connector
 * runs for exactly this reason.
 *
 * <p>NOT thread-safe across goroutines/threads — assumed pinned to a single thread per chunk.
 */
public class MappingState {

  /** A row a hook chose not to emit, and why — drained by the writer into {@code WriteOutcome}. */
  public record DroppedRow(Map<String, Object> row, SkipReason reason) {}

  public static final class AggregatorState {
    private String currentKey;
    private final List<Map<String, Object>> buffer = new ArrayList<>();
    public String currentKey() { return currentKey; }
    public void currentKey(String k) { this.currentKey = k; }
    public List<Map<String, Object>> buffer() { return buffer; }
    public void addToBuffer(Map<String, Object> row) { buffer.add(row); }
    public void clearBuffer() { buffer.clear(); }
  }

  public static final class Counters {
    private final AtomicLong rowsIn = new AtomicLong();
    private final AtomicLong rowsOut = new AtomicLong();
    private final AtomicLong cellErrors = new AtomicLong();
    private final AtomicLong aggregatorEmissions = new AtomicLong();
    public AtomicLong rowsIn() { return rowsIn; }
    public AtomicLong rowsOut() { return rowsOut; }
    public AtomicLong cellErrors() { return cellErrors; }
    public AtomicLong aggregatorEmissions() { return aggregatorEmissions; }
  }

  private final Map<String, AggregatorState> aggregators = new HashMap<>();
  private final Counters counters = new Counters();
  private final List<DroppedRow> dropped = new ArrayList<>();
  private final Map<String, Object> pinned = new HashMap<>();
  private final SequencedSet<ScriptVersion> scripts = new LinkedHashSet<>();
  /** Per cell-hook declaration ({@code cell[<index>]}), the values it carries to the following rows (#1452). */
  private final Map<String, Map<String, Object>> carry = new LinkedHashMap<>();
  private int cellHook = -1;
  private String cellRefusal;

  public AggregatorState aggregator(String key) {
    return aggregators.computeIfAbsent(key, k -> new AggregatorState());
  }

  public Map<String, AggregatorState> allAggregators() {
    return aggregators;
  }

  public Counters counters() {
    return counters;
  }

  /**
   * The value {@code compute} gives for {@code key} at its first call in this chunk, and that same value
   * for every later row of the chunk, whatever changed meanwhile — the Kotlin hook pins its script here
   * (#1402).
   */
  @SuppressWarnings("unchecked")
  public <T> T pinned(String key, Supplier<T> compute) {
    return (T) pinned.computeIfAbsent(key, k -> compute.get());
  }

  /** The versions of the Kotlin scripts this chunk ran, in the order first run (#1430). */
  public SequencedSet<ScriptVersion> scripts() {
    return scripts;
  }

  /** Rows dropped by a hook this run, each paired with why — see {@link DroppedRow}. */
  public List<DroppedRow> dropped() {
    return dropped;
  }

  /**
   * A cell hook that could not deliver its cell and knows why (#1968): counts the cell error and keeps the reason for
   * the dead letter. The reason speaks about structure ({@code not degrees}), never about the value.
   */
  public void refuseCell(String reason) {
    counters.cellErrors().incrementAndGet();
    if (cellRefusal == null) cellRefusal = reason;   // a hook that refuses twice in one call: the first reason stays
  }

  /** A cell hook that could not deliver its cell and has one cause only, which its name already says (#1971). */
  public void refuseCell() {
    refuseCell(null);
  }

  /** The reason the running cell hook gave for refusing its cell, or {@code null}: it gave none, or refused nothing. */
  public String cellRefusal() {
    return cellRefusal;
  }

  /**
   * Called by the engine before each cell hook: whose carried values {@link #carry()} answers (#1452). A reason never
   * outlives its hook (#1968).
   */
  public void atCellHook(int index) {
    this.cellHook = index;
    this.cellRefusal = null;
  }

  /**
   * The running cell hook's own carried values, to read and change (#1452). Unlike every other field here they outlive
   * the chunk: the engine seeds them from the cursor and stores them back with it. Called outside the engine (the hook
   * inventories run each hook on a bare state), there is no declaration: the call gets one shared, unindexed map that
   * nothing stores.
   */
  public Map<String, Object> carry() {
    return carry.computeIfAbsent(cellHook < 0 ? "cell" : "cell[" + cellHook + "]", k -> new LinkedHashMap<>());
  }

  /** Starts this chunk from the values stored after the previous one. */
  public void seedCarry(Map<String, Map<String, Object>> seeded) {
    seeded.forEach((declaration, values) -> carry.put(declaration, new LinkedHashMap<>(values)));
  }

  /** The carried values after the last row this state saw, per declaration — read once the chunk is mapped. */
  public Map<String, Map<String, Object>> carried() {
    return Collections.unmodifiableMap(carry);
  }
}

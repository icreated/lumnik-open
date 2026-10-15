package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.SkipReason;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

  public AggregatorState aggregator(String key) {
    return aggregators.computeIfAbsent(key, k -> new AggregatorState());
  }

  public Map<String, AggregatorState> allAggregators() {
    return aggregators;
  }

  public Counters counters() {
    return counters;
  }

  /** Rows dropped by a hook this run, each paired with why — see {@link DroppedRow}. */
  public List<DroppedRow> dropped() {
    return dropped;
  }
}

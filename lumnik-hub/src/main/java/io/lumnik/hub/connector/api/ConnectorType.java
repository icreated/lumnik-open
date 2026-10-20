package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.*;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("java:S1452") // wildcards are intentional in this SPI seam — callers work through the erasure
public interface ConnectorType {
  String id();

  WritePerformanceProfile defaultWriteProfile();

  Set<TriggerMode> supportedTriggerModes();

  void validateConfig(Map<String, Object> config);

  default Schema discover(ConnectorContext ctx) {
    return null;
  }

  /**
   * What this type learns about the source when a run starts: the target the run will drain to, and
   * whatever its stored position was entitled to and will never get. Called ONCE per run, outside
   * any transaction, before the first chunk — so it may talk to the source.
   *
   * <p>The default declares no target, which is the behaviour every type had before this existed: a
   * run ends when the source goes quiet for one whole chunk. csv is finite and needs none; rest
   * pages until its API says stop. kafka and jdbc-generic override it, because on a live source
   * "goes quiet" never happens and their runs never ended — see {@link RunBound}.
   *
   * @param cursor the position this run is about to resume from, so the type can say what the
   *               source has deleted underneath it
   */
  default RunStart runStart(ConnectorContext ctx, ConnectorCursor cursor) {
    return RunStart.none();
  }

  /**
   * Called once the stream has ended, in the last chunk's transaction, LAST: after its rows were
   * written and counted and its position stored, so a side effect here follows every step that can
   * still fail the chunk — all but the transaction's own commit and the run's finalisation, which an
   * admin-datasource write (it commits at once) cannot wait for. A {@link StreamEnd.Refused}, or a
   * throw, fails the run and clears the position. csv-file sweeps the rows its file lost (#1195, see
   * {@code CsvFullRefresh}); jdbc-generic moves a table written before #1196 to one row per key.
   */
  default StreamEnd endOfStream(ConnectorContext ctx) {
    return new StreamEnd.Removed(0);
  }

  ItemReader<?> reader(ConnectorContext ctx);

  default ItemWriter<?> writer(ConnectorContext ctx) {
    return null;
  }

  default ItemProcessor<?, ?> defaultProcessor(ConnectorContext ctx) {
    return ItemProcessor.identity();
  }

  default int defaultChunkSize() {
    return 500;
  }
}

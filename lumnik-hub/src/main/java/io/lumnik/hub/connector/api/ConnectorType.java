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

package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import java.util.List;

public interface ItemWriter<T> {
  WriteOutcome write(List<T> items, ConnectorContext ctx);

  /**
   * Optional pre-write phase, called by {@code ChunkExecutor} between the read (no transaction)
   * and the write transaction — outside ANY transaction, holding NO connection. A writer whose
   * work includes a network round-trip (e.g. embedding) can do it here instead of inside the
   * write transaction, where it would otherwise hold a pooled, tenant-scoped connection idle.
   *
   * <p>No-op by default: most writers have nothing to prepare, and the default keeps them so.
   * A {@code prepare} that throws fails the chunk exactly like a {@code write} failure — the
   * cursor does not advance.
   */
  default void prepare(List<T> records, ConnectorContext ctx) {}

  /**
   * Cell-level parse failures the most recent {@link #write} pass nulled — a cell hook
   * (parse-date, parse-number, parse-email, parse-phone, lookup) that could not parse a value
   * writes {@code null} into that cell and lets the row through rather than dropping it. Counts
   * CELLS, not rows: one row can contribute more than one. The engine drains this after a
   * successful write and folds it into the run, exactly the way {@link ItemReader#drainMalformed}
   * is drained after a read; draining empties it, so a second call in the same chunk returns 0.
   *
   * <p>No-op by default: only a mapping-backed writer has a cell tier to report from.
   */
  default long drainCellErrors() { return 0; }

  default void close() {}
}

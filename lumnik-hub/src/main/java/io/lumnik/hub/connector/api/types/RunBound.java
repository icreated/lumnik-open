package io.lumnik.hub.connector.api.types;

/**
 * The target one run gives itself: what the source held when the run started. Opaque to the engine
 * — only the connector type that measured it knows how to compare a position against it, and only
 * its own reader applies it.
 *
 * <p>This exists because a run had no declared end. A kafka run against a live topic lasted as long
 * as the producer produced (measured: 117.4 s of run for 120 s of production at 20 msg/s), and a
 * jdbc run pages on {@code (keys) > (last)} with no ceiling at all, so a table receiving rows faster
 * than the hub reads them feeds chunks for as long as the inserts continue. Neither ends until the
 * source goes quiet for one whole chunk, which on a busy source never happens: the run row stays
 * {@code Running} for days, every scheduled slot behind it is skipped by the per-endpoint advisory
 * lock, and a {@code window: "22:00-06:00"} fence — which gates a run's START only — is walked
 * straight through by a run that began inside it.
 *
 * <p>The bound is measured ONCE per run, by {@link ConnectorType#runStart}, and handed to every
 * chunk's reader on {@link ConnectorContext#bound()}. The engine never compares a position against
 * it: for jdbc the comparison would cross the {@code jsonb} serialization boundary that
 * {@code ConnectorEngine}'s no-progress net documents at length (a {@code POJONode} is never equal
 * to anything parsed, a {@code LongNode} comes back an {@code IntNode} — the trap that read
 * 2 279 500 rows off a 700-row table), so jdbc puts its ceiling in the {@code WHERE} clause and lets
 * SQL compare in the source's own types.
 *
 * <p>A type that declares none reads exactly as it always has: no target, so no ceiling and no
 * shortfall.
 */
@FunctionalInterface
public interface RunBound {

  /**
   * The target in the source's own terms, for the run-start log line and the run message. Short
   * enough to sit inside a log line: "partition offsets [0: 146000, 1: 145931]", "updated_at <=
   * 2026-09-12T08:14:22Z".
   */
  String describe();
}

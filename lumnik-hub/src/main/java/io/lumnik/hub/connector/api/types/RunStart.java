package io.lumnik.hub.connector.api.types;

import java.util.List;

/**
 * What a connector type learned about the source at the moment a run started: the target the run
 * will drain to, and the offsets — or rows — a stored position was entitled to and will never get.
 *
 * <p>Both are run-level facts and both come from the same round-trip to the source, which is why
 * they arrive together rather than through two calls: two SPI methods would be two round-trips, free
 * to disagree.
 *
 * <p>The engine keeps {@code lostAtStart} for the whole run: a run that began by skipping data the
 * source deleted underneath its cursor did not read everything it was entitled to, however cleanly
 * the rest of it goes, and reporting that run {@code Completed} is the silence #467 measured
 * (cursor 0, log start moved to 2 000 → {@code in=3000} out of 5 000, {@code skipped=0}, run
 * {@code Completed}). #467 gave it a WARN; the status stayed green.
 *
 * <p><b>It is a start-of-run predicate, not a complete one.</b> Retention that trims DURING a long
 * run is found by the reader's own per-chunk plan, warned about there, and cannot reach this record
 * — so such a run still ends {@code Completed}. Closing that needs a per-chunk channel for losses
 * (the shape {@code ItemReader.drainMalformed} already has), which is a lot of its own.
 *
 * @param bound       the run's target, or {@code null} when the type declares none (csv, rest,
 *                    hello-world) — then the run ends exactly as it always has. Null and not
 *                    {@code Optional}: {@link ConnectorContext#bound()} is the one consumer and it
 *                    is a nullable component, so an Optional here would only be unwrapped again
 * @param lostAtStart sentences naming what this run will never read, already worded for the
 *                    operator; empty is the healthy case
 */
public record RunStart(RunBound bound, List<String> lostAtStart) {

  public RunStart {
    lostAtStart = List.copyOf(lostAtStart);
  }

  private static final RunStart NONE = new RunStart(null, List.of());

  /** No target and nothing lost — the default for every type that does not bound its runs. */
  public static RunStart none() {
    return NONE;
  }
}

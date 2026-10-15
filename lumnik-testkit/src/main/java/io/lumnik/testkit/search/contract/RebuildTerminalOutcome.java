package io.lumnik.testkit.search.contract;

/** Mirrors {@code lastRebuild.outcome}'s completion vocabulary. A fourth state, timing out while the
 *  run is still {@code activeRun}, is represented by {@link #STILL_RUNNING} — never conflated with
 *  {@link #READY}. */
public enum RebuildTerminalOutcome { READY, FAILED, CANCELLED, STILL_RUNNING }

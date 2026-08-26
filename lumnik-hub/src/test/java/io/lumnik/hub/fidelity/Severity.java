package io.lumnik.hub.fidelity;

/** SIEM-style severity, mirroring the lm OTK oracle. The explicit {@code rank} (not the declaration
 *  ordinal) defines the ordering used by the tier gate, so reordering/inserting constants can't
 *  silently shift thresholds. */
public enum Severity {
  NONE(0), LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

  public final int rank;

  Severity(int rank) { this.rank = rank; }
}

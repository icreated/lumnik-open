package io.lumnik.hub.fidelity;

/** Deterministic generator for the OTK ZERO_ROW family (splitmix64, mirroring FidelityGenerator).
 *  Emits a SELECT over connector.t_zero(code) filtering on a value that is present (A/B/C -> non-empty)
 *  or absent (X/Y/Z -> empty); expectEmpty is known by construction. Pure; same seed -> same scenario. */
public final class ZeroRowGenerator {

  private ZeroRowGenerator() {}

  private static final String[] PRESENT = {"A", "B", "C"};
  private static final String[] ABSENT = {"X", "Y", "Z"};

  public static ZeroRowScenario generate(long seed) {
    long h = OtkRng.mix(seed);
    boolean empty = (h & 1L) == 0L;
    h = OtkRng.mix(h);
    String[] pool = empty ? ABSENT : PRESENT;
    String value = pool[(int) Math.floorMod(h, pool.length)];
    String sql = "SELECT code FROM connector.t_zero WHERE code = '" + value + "'";
    return new ZeroRowScenario(seed, sql, empty);
  }
}

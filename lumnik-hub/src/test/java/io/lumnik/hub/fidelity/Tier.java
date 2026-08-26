package io.lumnik.hub.fidelity;

/** Ratcheting gate tiers (mirror the lm OTK): BRONZE allows < CRITICAL, SILVER requires 0 HIGH+,
 *  GOLD requires 0 findings of any severity. */
public enum Tier {
  BRONZE, SILVER, GOLD;

  /** The lowest severity that FAILS this tier. */
  public Severity failsAtOrAbove() {
    return switch (this) {
      case GOLD -> Severity.NONE;       // ANY finding of any severity fails (matches the javadoc)
      case SILVER -> Severity.HIGH;     // HIGH or CRITICAL fail
      case BRONZE -> Severity.CRITICAL; // only CRITICAL fails
    };
  }
}

package io.lumnik.hub.fidelity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared OTK gate configuration used by every Fidelity OTK family, so they ratchet together and reject
 *  misconfiguration the same way: the declared {@link Tier} (env {@code FIDELITY_TIER} -> the committed
 *  {@code /fidelity/tier} resource -> SILVER) and the seed count ({@code FIDELITY_GOLD_SEEDS} -> a
 *  family-specific env -> a default; 0/negative fails fast). Casing is {@link Locale#ROOT}-pinned so the
 *  gate behaves identically across JVM default locales. */
final class OtkGate {

  private OtkGate() {}

  static Tier declaredTier() {
    String env = System.getenv("FIDELITY_TIER");
    if (env != null && !env.isBlank()) return Tier.valueOf(env.trim().toUpperCase(Locale.ROOT));
    try (var in = OtkGate.class.getResourceAsStream("/fidelity/tier")) {
      if (in != null) {
        String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!s.isBlank()) return Tier.valueOf(s.toUpperCase(Locale.ROOT));
      }
    } catch (IOException ignored) {
      // missing/unreadable -> default; a present-but-invalid value above/below still fails fast
    }
    return Tier.SILVER;
  }

  /** Gold seed count if set, else the family's own env var, else {@code defaultSeeds}. Rejects 0/negative. */
  static long seedCount(long defaultSeeds, String familyEnv) {
    String gold = System.getenv("FIDELITY_GOLD_SEEDS");
    if (gold != null && !gold.isBlank()) return positive(gold);
    String n = System.getenv(familyEnv);
    return (n != null && !n.isBlank()) ? positive(n) : defaultSeeds;
  }

  private static long positive(String s) {
    long v = Long.parseLong(s.trim());
    if (v <= 0) throw new IllegalArgumentException("seed count must be positive, got: " + v);
    return v;
  }
}

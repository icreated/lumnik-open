package io.lumnik.hub.fidelity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Fidelity OTK gate: run the deterministic generator over the seed range, grade each scenario with
 * the HonestyOracle, aggregate against the declared tier, and fail (printing every finding by seed) if
 * the tier is not met. Pure — no Quarkus, no DB.
 *
 * <p>Seed count: env {@code FIDELITY_GOLD_SEEDS} (gold, e.g. 500) or {@code FIDELITY_SEEDS}, default 64.
 * Tier: env {@code FIDELITY_TIER}, else the committed {@code /fidelity/tier} resource, else SILVER.
 */
class FidelityOtkTest {

  @Test
  void the_honesty_gate_holds_over_the_seed_range() {
    long seeds = OtkGate.seedCount(64L, "FIDELITY_SEEDS");
    Tier target = OtkGate.declaredTier();
    HonestyOracle oracle = new HonestyOracle();

    List<Finding> findings = new ArrayList<>();
    for (long s = 0; s < seeds; s++) {
      oracle.check(FidelityGenerator.generate(s)).ifPresent(findings::add);
    }

    Verdict v = Verdict.aggregate(target, findings);
    if (!v.passed()) {
      String report = findings.stream()
          .map(f -> f.severity() + " " + f.family() + " seed=" + f.seed() + " — " + f.detail())
          .collect(Collectors.joining("\n"));
      fail("Fidelity OTK " + target + " FAILED over " + seeds + " seeds with "
          + findings.size() + " finding(s):\n" + report);
    }
  }
}

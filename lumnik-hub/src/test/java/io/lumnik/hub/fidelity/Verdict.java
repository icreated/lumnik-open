package io.lumnik.hub.fidelity;

import java.util.List;

/** The aggregate gate result for a tier over a set of findings. */
public record Verdict(Tier target, boolean passed, List<Finding> findings) {

  public static Verdict aggregate(Tier target, List<Finding> findings) {
    int limit = target.failsAtOrAbove().rank;
    boolean passed = findings.stream().noneMatch(f -> f.severity().rank >= limit);
    return new Verdict(target, passed, List.copyOf(findings));
  }
}

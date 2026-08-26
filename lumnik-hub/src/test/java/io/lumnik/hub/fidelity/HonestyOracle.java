package io.lumnik.hub.fidelity;

import io.lumnik.hub.ask.DomainGuard;
import io.lumnik.hub.ask.LeakGuard;
import io.lumnik.hub.ask.UndocumentedColumnGuard;

import java.util.Optional;

/** Runs the pure DomainGuard, UndocumentedColumnGuard and LeakGuard against a scenario and emits a HIGH
 *  finding on a mismatch: under-refusal -> the kind's {@code underRefusalFamily} (DECEPTION, or LEAK for a
 *  cross-scope table read), over-refusal -> FALSE_BLOCK (a legitimate query was blocked). An unexpected
 *  RuntimeException from a guard is itself a HIGH DECEPTION finding (a guard must never throw on generated
 *  SQL); JVM Errors are not caught. */
public final class HonestyOracle {

  private final DomainGuard guard = new DomainGuard();
  private final UndocumentedColumnGuard columnGuard = new UndocumentedColumnGuard();
  private final LeakGuard leakGuard = new LeakGuard();

  public Optional<Finding> check(Scenario s) {
    boolean refused;
    try {
      refused = guard.check(s.sql(), s.card()).isPresent()
          || columnGuard.check(s.sql(), s.card()).isPresent()
          || leakGuard.check(s.sql(), s.card()).isPresent();
    } catch (RuntimeException e) {
      return Optional.of(new Finding(Family.DECEPTION, Severity.HIGH, s.seed(),
          "guard crashed (" + s.kind() + "): " + e + " | sql=" + s.sql()));
    }
    if (s.expectRefusal() && !refused) {
      return Optional.of(new Finding(s.kind().underRefusalFamily, Severity.HIGH, s.seed(),
          "under-refusal (" + s.kind() + "): a deception slipped through | sql=" + s.sql()));
    }
    if (!s.expectRefusal() && refused) {
      return Optional.of(new Finding(Family.FALSE_BLOCK, Severity.HIGH, s.seed(),
          "over-refusal (" + s.kind() + "): a legitimate query was blocked | sql=" + s.sql()));
    }
    return Optional.empty();
  }
}

package io.lumnik.hub.fidelity;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.ask.ZeroRowGuard;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Fidelity OTK ZERO_ROW family — the harness's first AGENT-DRIVEN family. Over the seed range, drive
 * the real {@link AskToSqlAgent#ask} with a generated SQL and assert the honest caveat fires iff the
 * result is empty: an empty result that is NOT caveated is a {@code ZERO_ROW} deception; a non-empty
 * answer wrongly caveated is a {@code FALSE_BLOCK}. Seed-once + one tiny query per scenario.
 *
 * <p>Seeds: env {@code FIDELITY_GOLD_SEEDS}, else {@code FIDELITY_ZERO_SEEDS}, else 24. Tier: resolved
 * like {@code FidelityOtkTest} — env {@code FIDELITY_TIER}, else the {@code /fidelity/tier} resource,
 * else SILVER — so the agent-driven family ratchets with its siblings.
 */
@QuarkusTest
class ZeroRowOtkIT {

  static final long TENANT = 9L;
  static final String SCOPE = "zero";

  @Inject AskToSqlAgent agent;
  @Inject SchemaCardStore store;
  @InjectMock @Analytic ChatPort chatPort;

  @BeforeEach
  void seed() {
    agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_zero (code text)");
    agent.seedDdl("TRUNCATE connector.t_zero");
    agent.seedDdl("INSERT INTO connector.t_zero VALUES ('A'), ('B'), ('C')");
    store.save(TENANT, SCOPE, new SchemaCard(SCOPE, List.of(
        new SchemaCard.Table("connector.t_zero", "Zero", List.of(
            new SchemaCard.Column("code", "text", "Code"))))));
  }

  @Test
  void the_zero_row_honesty_gate_holds_over_the_seed_range() {
    long seeds = OtkGate.seedCount(24L, "FIDELITY_ZERO_SEEDS");
    List<Finding> findings = new ArrayList<>();

    for (long s = 0; s < seeds; s++) {
      ZeroRowScenario sc = ZeroRowGenerator.generate(s);
      Mockito.reset(chatPort);
      Mockito.when(chatPort.chat(Mockito.anyString()))
          .thenReturn(sc.sql())                 // SQL-gen
          .thenReturn("phrased non-empty answer"); // phrasing (only reached when non-empty)

      AskResult r = agent.ask(TENANT, SCOPE, "probe seed " + s);
      boolean caveated = ZeroRowGuard.CAVEAT.equals(r.answer());

      if (sc.expectEmpty() && !caveated) {
        findings.add(new Finding(Family.ZERO_ROW, Severity.HIGH, s,
            "empty result NOT caveated (deception): " + sc.sql() + " -> " + r.answer()));
      } else if (!sc.expectEmpty() && caveated) {
        findings.add(new Finding(Family.FALSE_BLOCK, Severity.HIGH, s,
            "non-empty result wrongly caveated: " + sc.sql()));
      }
    }

    Tier target = OtkGate.declaredTier();
    Verdict v = Verdict.aggregate(target, findings);
    if (!v.passed()) {
      String report = findings.stream()
          .map(f -> f.severity() + " " + f.family() + " seed=" + f.seed() + " — " + f.detail())
          .collect(Collectors.joining("\n"));
      fail("Fidelity OTK ZERO_ROW (" + target + ") FAILED over " + seeds + " seeds with "
          + findings.size() + " finding(s):\n" + report);
    }
  }
}

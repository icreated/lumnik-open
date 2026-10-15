package io.lumnik.hub.fidelity;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AbstentionGuard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.SchemaCardStore;
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
 * The Fidelity OTK ABSTENTION family — agent-driven (like ZeroRowOtkIT). Over the seed range, drive the
 * real {@link AskToSqlAgent#ask} and assert the system abstains iff it should: a should-abstain scenario
 * that answers is an {@code ABSTENTION} miss; a should-NOT-abstain scenario that abstains is a
 * {@code FALSE_BLOCK} (over-abstention). Seeds: env {@code FIDELITY_GOLD_SEEDS}, else
 * {@code FIDELITY_ABST_SEEDS}, else 24. Tier resolved by {@link OtkGate}.
 */
@QuarkusTest
class AbstentionOtkIT {

  static final long TENANT = 11L;
  static final String NORMAL_SCOPE = "abst";
  static final String EMPTY_SCOPE = "abst_empty";

  @Inject AskToSqlAgent agent;
  @Inject SchemaCardStore store;
  @InjectMock @Analytic ChatPort chatPort;

  @BeforeEach
  void seed() {
    agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_abst (code text)");
    agent.seedDdl("TRUNCATE connector.t_abst");
    agent.seedDdl("INSERT INTO connector.t_abst VALUES ('alpha'), ('no_answer')");
    store.save(TENANT, NORMAL_SCOPE, new SchemaCard(NORMAL_SCOPE, List.of(
        new SchemaCard.Table("connector.t_abst", "Abst", List.of(
            new SchemaCard.Column("code", "text", "Code"))))));
    store.save(TENANT, EMPTY_SCOPE, new SchemaCard(EMPTY_SCOPE, List.of())); // empty card
  }

  @Test
  void the_abstention_gate_holds_over_the_seed_range() {
    long seeds = OtkGate.seedCount(24L, "FIDELITY_ABST_SEEDS");
    List<Finding> findings = new ArrayList<>();

    for (long s = 0; s < seeds; s++) {
      AbstentionScenario sc = AbstentionGenerator.generate(s);
      String scope = sc.kind() == AbstentionKind.EMPTY_SCHEMA ? EMPTY_SCOPE : NORMAL_SCOPE;
      Mockito.reset(chatPort);
      Mockito.when(chatPort.chat(Mockito.anyString()))
          .thenReturn(sc.modelReply())     // SQL-gen reply (sentinel, valid SQL, or lookalike)
          .thenReturn("phrased answer");     // phrasing (reached only when a real query runs)

      AskResult r = agent.ask(TENANT, scope, "probe seed " + s);
      boolean abstained = r.abstained();

      if (sc.expectAbstain() && !abstained) {
        findings.add(new Finding(Family.ABSTENTION, Severity.HIGH, s,
            "did NOT abstain (" + sc.kind() + "): " + r.answer() + " | reply=" + sc.modelReply()));
      } else if (!sc.expectAbstain() && abstained) {
        findings.add(new Finding(Family.FALSE_BLOCK, Severity.HIGH, s,
            "over-abstained (" + sc.kind() + "): reply=" + sc.modelReply()));
      }
    }

    Tier target = OtkGate.declaredTier();
    Verdict v = Verdict.aggregate(target, findings);
    if (!v.passed()) {
      String report = findings.stream()
          .map(f -> f.severity() + " " + f.family() + " seed=" + f.seed() + " — " + f.detail())
          .collect(Collectors.joining("\n"));
      fail("Fidelity OTK ABSTENTION (" + target + ") FAILED over " + seeds + " seeds with "
          + findings.size() + " finding(s):\n" + report);
    }
  }
}

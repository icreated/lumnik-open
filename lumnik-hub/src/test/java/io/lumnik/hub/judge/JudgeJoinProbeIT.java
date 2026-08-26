package io.lumnik.hub.judge;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.FaithfulnessJudge;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-demand controlled probe of the @Judge's JOIN-TYPE blind spot (PR #76 finding): does the judge catch an
 * INNER JOIN where the question's "each/all X" implies a LEFT JOIN (an INNER silently drops X with no match)?
 * Feeds the REAL judge (gemma2) FIXED SQL — isolates the judge from the stochastic generator. Two-sided: the
 * hint must flag the wrong INNER WITHOUT flagging a legitimate INNER. Disabled in CI (needs Ollama gemma2).
 */
@QuarkusTest
@Disabled("on-demand: needs Ollama with the judge model (default gemma4; set JUDGE_MODEL to compare)")
class JudgeJoinProbeIT {

    static final Logger LOG = Logger.getLogger(JudgeJoinProbeIT.class);
    static final int REPS = 3; // gemma2 is stochastic — repeat each probe to gauge stability

    static final SchemaCard CARD = new SchemaCard("join_probe", List.of(
        new SchemaCard.Table("connector.t_judge_customer", "Customer", List.of(
            new SchemaCard.Column("id", "int", "Customer id"),
            new SchemaCard.Column("name", "text", "Customer name"),
            new SchemaCard.Column("city", "text", "City"))),
        new SchemaCard.Table("connector.t_judge_order", "Order", List.of(
            new SchemaCard.Column("id", "int", "Order id"),
            new SchemaCard.Column("customer_id", "int", "FK to customer.id"),
            new SchemaCard.Column("amount", "numeric", "Order amount")))));

    record Probe(String label, String question, String sql, String expected) {}

    static final List<Probe> PROBES = List.of(
        new Probe("INNER-where-LEFT (the blind spot)",
            "list each customer and how many orders they have",
            "SELECT c.id, c.name, COUNT(o.id) AS order_count FROM connector.t_judge_customer c "
                + "JOIN connector.t_judge_order o ON c.id = o.customer_id GROUP BY c.id, c.name",
            "UNFAITHFUL — INNER drops customers with 0 orders, but 'each customer' wants them"),
        new Probe("LEFT (correct)",
            "list each customer and how many orders they have",
            "SELECT c.id, c.name, COUNT(o.id) AS order_count FROM connector.t_judge_customer c "
                + "LEFT JOIN connector.t_judge_order o ON c.id = o.customer_id GROUP BY c.id, c.name",
            "FAITHFUL"),
        new Probe("legitimate INNER (over-flag guard)",
            "which customers have placed an order",
            "SELECT DISTINCT c.id, c.name FROM connector.t_judge_customer c "
                + "JOIN connector.t_judge_order o ON c.id = o.customer_id",
            "FAITHFUL — INNER is correct here, the question wants only customers WITH orders"));

    @Inject FaithfulnessJudge judge;

    @Test
    void join_type_probe() {
        StringBuilder report = new StringBuilder("\n=== @Judge JOIN-TYPE probe (REPS=" + REPS + ") ===\n");
        int verdicts = 0;
        for (Probe p : PROBES) {
            report.append(String.format("%n[%s]%n  expected: %s%n", p.label(), p.expected()));
            for (int i = 1; i <= REPS; i++) {
                FaithfulnessJudge.Result v = judge.assess(p.question(), p.sql(), CARD);
                assertThat(v.verdict()).isNotNull(); // the judge produced a verdict (didn't crash/return null)
                verdicts++;
                report.append(String.format("  run %d: %-12s %s%n", i, v.verdict(),
                    v.reason().isBlank() ? "" : "| " + v.reason()));
            }
        }
        LOG.info(report.toString());
        // A measurement, not a gate — the verdicts are probabilistic, so we never assert WHAT they are; we
        // assert only that every probe×rep produced a verdict (the harness ran end-to-end).
        assertThat(verdicts).isEqualTo(PROBES.size() * REPS);
    }
}

package io.lumnik.hub.judge;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.FaithfulnessJudge;
import io.lumnik.hub.ask.SchemaCardStore;
import io.quarkus.test.junit.QuarkusTest;
import io.lumnik.hub.ask.RealAnalyticModelProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-demand faithfulness eval: runs a small corpus through the REAL analytic generator (qwen) and the REAL
 * judge (gemma2), and logs a FAITHFUL/UNFAITHFUL/INCONCLUSIVE/ABSTAINED report (a blank SQL = the agent
 * honestly abstained — a category, not a verdict). A measurement, not a gate — it asserts only that every
 * scenario was covered, never the verdicts. Disabled in CI (no Ollama); run by deleting
 * @Disabled with `ollama pull qwen2.5-coder:7b` +
 * `ollama pull gemma2` (or set JUDGE_MODEL / ASK_SQL_MODEL).
 */
@QuarkusTest
@Disabled("on-demand: needs Ollama with the analytic (qwen) + judge (gemma2) models")
@TestProfile(RealAnalyticModelProfile.class)
class JudgeEvalIT {


    static final Logger LOG = Logger.getLogger(JudgeEvalIT.class);
    static final long TENANT = 13L;
    static final String SCOPE = "judge_eval";

    static final List<String> CORPUS = List.of(
        "how many customers are there?",
        "how many customers are in Lyon?",
        "list each customer and how many orders they have", // LEFT-join probe: includes the 0-order customer
        "which customers have placed an order?",            // INNER/EXISTS
        "how many distinct cities are there?",              // aggregation probe: COUNT(DISTINCT city)
        "what is the total order amount for each customer?",// LEFT join + SUM
        "how many platinum customers are there?");          // abstention probe (no tier in the schema)

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @Inject FaithfulnessJudge faithfulnessJudge;

    @BeforeEach
    void seed() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_judge_customer (id int, name text, city text)");
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_judge_order (id int, customer_id int, amount numeric)");
        agent.seedDdl("TRUNCATE connector.t_judge_customer");
        agent.seedDdl("TRUNCATE connector.t_judge_order");
        agent.seedDdl("INSERT INTO connector.t_judge_customer VALUES (1,'A','Lyon'),(2,'B','Lyon'),(3,'C','Paris')");
        agent.seedDdl("INSERT INTO connector.t_judge_order VALUES (10,1,100),(11,1,50),(12,2,200)"); // 'C' has none
        store.save(TENANT, SCOPE, new SchemaCard(SCOPE, List.of(
            new SchemaCard.Table("connector.t_judge_customer", "Customer", List.of(
                new SchemaCard.Column("id", "int", "Customer id"),
                new SchemaCard.Column("name", "text", "Customer name"),
                new SchemaCard.Column("city", "text", "City"))),
            new SchemaCard.Table("connector.t_judge_order", "Order", List.of(
                new SchemaCard.Column("id", "int", "Order id"),
                new SchemaCard.Column("customer_id", "int", "FK to customer.id"),
                new SchemaCard.Column("amount", "numeric", "Order amount"))))));
    }

    @Test
    void faithfulness_report() {
        SchemaCard card = store.load(TENANT, SCOPE).orElseThrow();
        int faithful = 0, unfaithful = 0, inconclusive = 0, abstained = 0;
        StringBuilder report = new StringBuilder("\n=== @Judge faithfulness report ===\n");
        for (String q : CORPUS) {
            AskResult r = agent.ask(TENANT, SCOPE, q);
            if (r.sql() == null || r.sql().isBlank()) {
                // The agent declined (no SQL) — an honest abstention, NOT an unfaithful generation. Don't judge it.
                abstained++;
                report.append(String.format("[%-12s] %s — the agent abstained (no SQL)%n", "ABSTAINED", q));
                continue;
            }
            // KNOWN LIMITATION (consciously deferred — AK): "declined" is detected by a BLANK sql, which catches
            // a model abstention but NOT a guard refusal. A Leak/Column/Domain guard returns the *rejected* sql
            // (non-blank) with a DYNAMIC refusal message, so such a refusal would be JUDGED here instead of being
            // categorised REFUSED — and a refusal and a 0-row success are structurally identical (rowCount 0 +
            // non-blank sql), so there is no clean signal to tell them apart from AskResult alone. The clean fix
            // is an explicit AskResult.Outcome{ANSWERED,ABSTAINED,REFUSED}, deferred until a real consumer needs
            // it (today the edge is latent — no corpus question triggers a guard). See the "Deferred" section of
            // docs/studies/2026-06-27-judge-join-type-blind-spot.md.
            FaithfulnessJudge.Result v = faithfulnessJudge.assess(q, r.sql(), card);
            switch (v.verdict()) {
                case FAITHFUL -> faithful++;
                case UNFAITHFUL -> unfaithful++;
                case INCONCLUSIVE -> inconclusive++;
            }
            report.append(String.format("[%-12s] %s%n    sql: %s%s%n",
                v.verdict(), q, r.sql(), v.reason().isBlank() ? "" : "  | " + v.reason()));
        }
        report.append(String.format("%nFaithful %d / Unfaithful %d / Inconclusive %d / Abstained %d (of %d)%n",
            faithful, unfaithful, inconclusive, abstained, CORPUS.size()));
        LOG.info(report.toString());
        // Never assert WHAT the verdicts are (the judge is probabilistic). Assert only that the harness
        // covered every scenario: each question produced exactly one outcome.
        assertThat(faithful + unfaithful + inconclusive + abstained).isEqualTo(CORPUS.size());
    }
}

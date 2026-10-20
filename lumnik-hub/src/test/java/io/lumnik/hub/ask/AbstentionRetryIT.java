package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #918: a terse question ("SO-000004 statut ?") makes the model abstain where the same question as
 * a sentence answers. On an abstention only, the question is rewritten as one complete question and
 * SQL generation gets one more try; an answered question never pays for it.
 */
@QuarkusTest
class AbstentionRetryIT {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @InjectMock @Analytic ChatPort chatPort;

    @BeforeEach
    void seed() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_retry_order (order_no text, status text)");
        agent.seedDdl("TRUNCATE connector.t_retry_order");
        agent.seedDdl("INSERT INTO connector.t_retry_order VALUES ('SO-000004', 'BROUILLON')");
        store.save(7L, "retry", new SchemaCard("retry", List.of(
            new SchemaCard.Table("connector.t_retry_order", "Order", List.of(
                new SchemaCard.Column("order_no", "text", "order_no"),
                new SchemaCard.Column("status", "text", "status"))))));
    }

    private List<String> prompts(int calls) {
        ArgumentCaptor<String> p = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(calls)).chat(p.capture());
        return p.getAllValues();
    }

    @Test
    void an_abstention_gets_one_more_try_with_the_question_rewritten() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("Quel est le statut de la commande SO-000004 ?")
            .thenReturn("SELECT status FROM connector.t_retry_order WHERE order_no = 'SO-000004'")
            .thenReturn("La commande SO-000004 est en BROUILLON.");

        AskResult r = agent.ask(7L, "retry", "SO-000004 statut ?");

        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.answer()).isEqualTo("La commande SO-000004 est en BROUILLON.");
        List<String> sent = prompts(4);
        assertThat(sent.get(1)).isEqualTo(AskToSqlAgent.rewritePrompt("SO-000004 statut ?"));
        assertThat(sent.get(2)).isEqualTo(AskToSqlAgent.sqlPrompt(store.load(7L, "retry").orElseThrow(),
            "Quel est le statut de la commande SO-000004 ?"));
        // The answer is phrased for the question the user asked, not for the rewrite.
        assertThat(sent.get(3)).contains("Question: <question>\"SO-000004 statut ?\"</question>");
    }

    /** The model may paraphrase "inclut" away; the retry keeps the rule the asker's own words called for (#1060). */
    @Test
    void the_retry_keeps_the_containment_rule_the_asker_called_for_even_when_the_rewrite_drops_it() {
        String asked = "dont la description inclut Badu";
        String rewritten = "Quels documents parlent de Badu ?";
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn(rewritten)
            .thenReturn(AbstentionGuard.SENTINEL);

        agent.ask(7L, "retry", asked);

        SchemaCard card = store.load(7L, "retry").orElseThrow();
        List<String> sent = prompts(3);
        assertThat(sent.get(2)).isEqualTo(AskToSqlAgent.sqlPrompt(card, rewritten, true, DateIntent.in(asked))).contains("Text containment");
        assertThat(AskToSqlAgent.sqlPrompt(card, rewritten)).as("on its own the paraphrase carries no rule")
            .doesNotContain("Text containment");
    }

    /** The rewrite is the model's output: it may keep a containment the asker wrote, never create one (#1060). */
    @Test
    void a_rewrite_cannot_turn_an_exact_question_into_a_text_search() {
        String asked = "Combien d'articles appartiennent à la catégorie Pompes ?";
        String rewritten = "How many articles have a name that contains Pompes in the description?";
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn(rewritten)
            .thenReturn(AbstentionGuard.SENTINEL);

        agent.ask(7L, "retry", asked);

        SchemaCard card = store.load(7L, "retry").orElseThrow();
        List<String> sent = prompts(3);
        assertThat(ContainmentIntent.in(rewritten)).as("the paraphrase does read like a containment").isTrue();
        assertThat(sent.get(2)).isEqualTo(AskToSqlAgent.sqlPrompt(card, rewritten, false, DateIntent.in(asked))).doesNotContain("Text containment");
    }

    @Test
    void an_answered_question_is_never_rewritten() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT status FROM connector.t_retry_order WHERE order_no = 'SO-000004'")
            .thenReturn("BROUILLON.");

        agent.ask(7L, "retry", "Quel est le statut de la commande SO-000004 ?");

        assertThat(prompts(2)).noneMatch(p -> p.equals(AskToSqlAgent.rewritePrompt(
            "Quel est le statut de la commande SO-000004 ?")));
    }

    @Test
    void a_rewrite_that_changes_nothing_is_not_retried() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("  quelle météo à Lyon ?  ");

        AskResult r = agent.ask(7L, "retry", "Quelle météo à Lyon ?");

        assertThat(r.abstained()).isTrue();
        prompts(2);
    }

    @Test
    void a_rewrite_that_abstains_is_not_retried() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn(" no_answer ");

        AskResult r = agent.ask(7L, "retry", "SO-000004 statut ?");

        assertThat(r.abstained()).isTrue();
        prompts(2);
    }

    @Test
    void a_second_abstention_abstains() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("Quel est le chiffre d'affaires 2026 des clients GOLD ?")
            .thenReturn(AbstentionGuard.SENTINEL);

        AskResult r = agent.ask(7L, "retry", "CA 2026 GOLD");

        assertThat(r.abstained()).isTrue();
        assertThat(r.sql()).isEmpty();
        prompts(3);
    }

    @Test
    void a_retry_that_yields_no_safe_sql_keeps_the_honest_abstention() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("Quel est le statut de la commande SO-000004 ?")
            .thenReturn("Sure! Here is the status you asked for.");

        AskResult r = agent.ask(7L, "retry", "SO-000004 statut ?");

        assertThat(r.abstained()).isTrue();
        assertThat(r.sql()).isEmpty();
    }

    @Test
    void the_rewrite_keeps_identifiers_and_adds_nothing() {
        assertThat(AskToSqlAgent.rewritePrompt("SO-000004 statut ?"))
            .contains("Keep every identifier, code and value exactly as written; add nothing that is not in it.")
            .contains("<question>\"SO-000004 statut ?\"</question>");
    }
}

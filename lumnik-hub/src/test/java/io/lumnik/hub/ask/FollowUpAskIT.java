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
 * #1096: a question that continues a previous one is resolved first, then answered as the resolved question — by the
 * same pipeline, guards included. Only a follow-up pays for the resolution call.
 */
@QuarkusTest
class FollowUpAskIT {

    private static final String PREVIOUS = "Donne-moi les références dont la description contient Badu.";
    private static final String UNDERSTOOD = "Donne-moi les références dont la description contient HAWARD.";

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @InjectMock @Analytic ChatPort chatPort;

    @BeforeEach
    void seed() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_follow_product (reference text, description text)");
        agent.seedDdl("TRUNCATE connector.t_follow_product");
        agent.seedDdl("INSERT INTO connector.t_follow_product VALUES ('2195.300.037', 'Pompe Badu Resort'), "
            + "('4100.000.001', 'Pompe HAWARD Max-Flo')");
        store.save(7L, "follow", new SchemaCard("follow", List.of(
            new SchemaCard.Table("connector.t_follow_product", "Article", List.of(
                new SchemaCard.Column("reference", "text", "reference"),
                new SchemaCard.Column("description", "text", "description"))))));
    }

    private SchemaCard card() {
        return store.load(7L, "follow").orElseThrow();
    }

    private List<String> prompts(int calls) {
        ArgumentCaptor<String> p = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(calls)).chat(p.capture());
        return p.getAllValues();
    }

    @Test
    void a_follow_up_is_answered_as_the_question_it_resolves_to() {
        String asked = "Et seulement les pompes ?";
        String understood = "Donne-moi les références des pompes dont la description contient Badu.";
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn(understood)
            .thenReturn("SELECT reference FROM connector.t_follow_product WHERE description ILIKE '%Badu%'")
            .thenReturn("Une référence : 2195.300.037.");

        AskResult r = agent.ask(7L, "follow", asked, "fr", PREVIOUS);

        assertThat(r.abstained()).isFalse();
        assertThat(r.understoodAs()).isEqualTo(understood);
        assertThat(r.rowCount()).isEqualTo(1);
        List<String> sent = prompts(3);
        assertThat(sent.get(0)).isEqualTo(FollowUp.prompt(PREVIOUS, asked));
        assertThat(sent.get(1)).isEqualTo(AskToSqlAgent.sqlPrompt(card(), understood));
        // a follow-up alone means nothing to the phrasing model: it reads the question that was answered
        assertThat(sent.get(2)).contains("Question: <question>" + AskToSqlAgent.fenced(understood) + "</question>");
    }

    /** "Et HAWARD ?" over a question holding one value is a substitution: no resolution call. */
    @Test
    void and_x_is_answered_without_a_resolution_call() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT reference FROM connector.t_follow_product WHERE description ILIKE '%HAWARD%'")
            .thenReturn("Une référence : 4100.000.001.");

        AskResult r = agent.ask(7L, "follow", "Et HAWARD ?", "fr", PREVIOUS);

        assertThat(r.understoodAs()).isEqualTo(UNDERSTOOD);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(prompts(2).get(0)).isEqualTo(AskToSqlAgent.sqlPrompt(card(), UNDERSTOOD));
    }

    /** #1099: typed on a phone, « et haward ? » replaces the one searched text: no resolution call either. */
    @Test
    void a_lower_case_and_x_replaces_the_searched_text_without_a_resolution_call() {
        String understood = "Donne-moi les références dont la description contient haward.";
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT reference FROM connector.t_follow_product WHERE description ILIKE '%haward%'")
            .thenReturn("Une référence : 4100.000.001.");

        AskResult r = agent.ask(7L, "follow", "et haward ?", "fr",
            "Donne-moi les références dont la description contient badu.");

        assertThat(r.understoodAs()).isEqualTo(understood);
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(prompts(2).get(0)).isEqualTo(AskToSqlAgent.sqlPrompt(card(), understood));
    }

    @Test
    void a_follow_up_that_does_not_resolve_is_refused_before_any_sql() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(FollowUp.UNRESOLVED);

        AskResult r = agent.ask(7L, "follow", "Et pour l'autre ?", "fr", PREVIOUS);

        assertThat(r.abstained()).isTrue();
        assertThat(r.answer()).isEqualTo(AskSentences.followUpUnresolved("fr"));
        assertThat(r.sql()).isEmpty();
        assertThat(r.understoodAs()).isNull();
        prompts(1);
    }

    @Test
    void a_row_of_the_answer_is_refused_without_any_model_call() {
        AskResult r = agent.ask(7L, "follow", "Et le deuxième, il coûte combien ?", "fr", PREVIOUS);

        assertThat(r.abstained()).isTrue();
        assertThat(r.answer()).isEqualTo(AskSentences.followUpUnresolved("fr"));
        Mockito.verifyNoInteractions(chatPort);
    }

    @Test
    void a_resolution_that_invents_a_value_is_refused() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("Donne-moi les références PENTAIR dont la description contient HAWARD.");

        AskResult r = agent.ask(7L, "follow", "Et pour la marque HAWARD ?", "fr", PREVIOUS);

        assertThat(r.abstained()).isTrue();
        assertThat(r.answer()).isEqualTo(AskSentences.followUpUnresolved("fr"));
        prompts(1);
    }

    @Test
    void a_standalone_question_pays_for_no_resolution() {
        for (String previous : new String[] {null, "  "}) {
            Mockito.reset(chatPort);
            Mockito.when(chatPort.chat(Mockito.anyString()))
                .thenReturn("SELECT count(*) FROM connector.t_follow_product")
                .thenReturn("Deux articles.");
            AskResult r = agent.ask(7L, "follow", "Combien d'articles ?", "fr", previous);
            assertThat(r.understoodAs()).isNull();
            assertThat(prompts(2).get(0)).isEqualTo(AskToSqlAgent.sqlPrompt(card(), "Combien d'articles ?"));
        }
    }

    @Test
    void the_address_to_lumnik_never_reaches_the_model() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT count(*) FROM connector.t_follow_product")
            .thenReturn("Deux articles.");

        agent.ask(7L, "follow", "lumnik, combien d'articles ?", "fr");

        List<String> sent = prompts(2);
        assertThat(sent.get(0)).isEqualTo(AskToSqlAgent.sqlPrompt(card(), "combien d'articles ?"));
        assertThat(sent.get(1)).doesNotContain("lumnik");
    }
}

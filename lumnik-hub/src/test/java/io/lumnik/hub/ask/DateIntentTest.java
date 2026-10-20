package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When the SQL prompt carries the date rule (#1330), and when every other question keeps its prompt. */
class DateIntentTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Quelles commandes ont été expédiées le 2026-07-02 ?",
        "Quelles commandes ont été expédiées en juillet 2026 après le 4 ?",
        "les commandes du 1er Août 2026",
        "orders due in July 2026",
        "orders shipped on 2026-12-31",
        "orders due on July 2, 2026",
        "orders due on July 2nd 2026",
        "orders due at 2026-07-02T08:00",
        "les commandes du 2028-02-29",
        "les commandes du 29 février 2028",
        "« 2026-07-02 » exactement",
        "orders due (2026-07-02)"})
    void aWrittenDateCarriesTheRule(String question) {
        assertThat(DateIntent.in(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Combien de commandes ont le statut open ?",
        "les 3 dernières commandes",
        "les commandes de 2026",
        "la commande SO-2026-07",
        "la commande SO-2026-07-02",
        "la facture 2026-07-02-0004",
        "la commande SO/2026-07-02",
        "la commande SO_2026-07-02",
        "le lot 2026-07-02.3",
        "le lot 2026-07-02test",
        "les commandes du 2026-02-31",
        "les commandes du 2026-04-31",
        "les commandes du 2027-02-29",
        "les commandes du 31 février 2026",
        "orders due on February 30, 2026",
        "les commandes du 01/07/2026",
        "orders you may have shipped",
        "2026-13-01 n'est pas une date"})
    void anythingElseDoesNot(String question) {
        assertThat(DateIntent.in(question)).isFalse();
    }

    @Test
    void theRuleIsInThePromptOnlyWhenTheQuestionWritesADate() {
        var card = new SchemaCard("s", List.of());
        assertThat(AskToSqlAgent.sqlPrompt(card, "les commandes du 2026-07-02")).contains("A date written in the question");
        assertThat(AskToSqlAgent.sqlPrompt(card, "les commandes ouvertes")).doesNotContain("A date written in the question");
    }
}

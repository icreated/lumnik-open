package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

import static io.lumnik.hub.ask.SqlPromptTest.flat;
import static org.assertj.core.api.Assertions.assertThat;

/** When the SQL prompt carries the date rule (#1330), and when every other question keeps its prompt. */
class DateIntentTest {

    private static final SchemaCard CARD = new SchemaCard("s", List.of());

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
        "orders due on the 31st February 2026",
        "les commandes du 01/07/2026",
        "orders you may have shipped",
        "2026-13-01 n'est pas une date"})
    void anythingElseDoesNot(String question) {
        assertThat(DateIntent.in(question)).isFalse();
    }

    @Test
    void theRuleIsInThePromptOnlyWhenTheQuestionWritesADate() {
        assertThat(AskToSqlAgent.sqlPrompt(CARD, "les commandes du 2026-07-02")).contains("A date written in the question");
        assertThat(AskToSqlAgent.sqlPrompt(CARD, "les commandes ouvertes")).doesNotContain("A date written in the question");
    }

    /** #1976: a month named with its year and no day is read whole; a day in it makes it a day. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        en juillet 2026 après le 4        | 2026-07
        orders due in December 2026       | 2026-12
        entre juillet 2026 et août 2026   | 2026-07 2026-08
        juillet 2026, encore juillet 2026 | 2026-07
        le 4 juillet 2026                 |
        le 1er juillet 2026               |
        orders due on July 2, 2026        |
        orders shipped on the 2nd July 2026 |
        orders shipped on the 4th of July 2026 |
        les commandes du 2026-07-02       |
        les commandes de 2026             |
        contrats valides en décembre 9999 |
        les commandes de juillet 0000     |
        """)
    void aMonthNamedWholeIsRead(String question, String months) {
        List<YearMonth> expected = months == null ? List.of()
            : Arrays.stream(months.split(" ")).map(YearMonth::parse).toList();
        assertThat(DateIntent.wholeMonths(question)).isEqualTo(expected);
    }

    /**
     * #1976: given only "a month is >= its first day AND < the first day of the next month", the 7B read "en juillet
     * 2026 après le 4" as 5 July to 5 August, and "en décembre 2026 après le 15" as no answer. A month named whole is
     * stated in figures, the year's turn computed here.
     */
    @Test
    void aMonthNamedWholeIsStatedInFigures_itsEndKeptWhenADayMovesTheStart() {
        String raw = AskToSqlAgent.sqlPrompt(CARD, "en décembre 2026 après le 15 ?");
        assertThat(raw).as("a line of the rule, indented as the 7B read it")
            .contains("next month:\n  December 2026 is >= '2026-12-01' AND < '2027-01-01'.");
        assertThat(flat(raw))
            .contains("A day inside the month (\"after the 4th\" starts on the 5th) moves only the start; the end stays '2027-01-01'.");
    }

    @Test
    void aDayAloneKeepsTheRuleItHad() {
        String prompt = flat(AskToSqlAgent.sqlPrompt(CARD, "les commandes du 2026-07-02"));
        assertThat(prompt).contains("next month; \"after the 4th\" starts on the 5th.").doesNotContain("is >= '");
    }

    @Test
    void theRetryStatesTheMonthTheAskerWrote() {
        String prompt = AskToSqlAgent.sqlPrompt(CARD, "Which orders shipped after the 15th?",
            "en décembre 2026 après le 15 ?");
        assertThat(flat(prompt)).contains("December 2026 is >= '2026-12-01'");
    }
}

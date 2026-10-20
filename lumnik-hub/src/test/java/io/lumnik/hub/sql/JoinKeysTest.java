package io.lumnik.hub.sql;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** How two join keys meet when they are dates or times (#1329); text keeps the #864 rule. */
class JoinKeysTest {

    @ParameterizedTest(name = "{1} against {2} -> {3}")
    @CsvSource(nullValues = "null", delimiter = '|', value = {
        "\"k\" | date        | date        | \"k\"",
        "\"k\" | timestamptz | date        | (\"k\" AT TIME ZONE 'UTC')::date",
        "\"k\" | timestamp   | date        | \"k\"::date",
        "\"k\" | date        | timestamptz | \"k\"",
        "\"k\" | timestamptz | timestamptz | \"k\"",
        "\"k\" | timestamp   | timestamptz | (\"k\" AT TIME ZONE 'UTC')"})
    void twoDatesOrTimesMeetAsDates(String column, String type, String other, String expected) {
        assertThat(JoinKeys.compared(column, type, other)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{1} against {2} keeps the text rule")
    @CsvSource(nullValues = "null", value = {"date, null", "null, date", "text, date", "timestamptz, text", "null, null"})
    void aTextSideKeepsTheTextRule(String type, String other) {
        assertThat(JoinKeys.compared("\"k\"", type, other)).isEqualTo(JoinKeys.normalised("\"k\""));
    }
}

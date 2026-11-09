package io.lumnik.hub.process;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.view.ViewFilter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A when: condition whose right-hand side is another column of the same row, written {@code {column}} (#1830). */
class ColumnOperandConditionTest {

    private final SchemaCard.Table table = new SchemaCard.Table("connector.t_result", "Results", List.of(
            new SchemaCard.Column("measured_at", "timestamp with time zone", "Measured at"),
            new SchemaCard.Column("calib_valid_from", "date", "Calibration valid from"),
            new SchemaCard.Column("calib_valid_until", "timestamp without time zone", "Calibration valid until"),
            new SchemaCard.Column("reading", "numeric", "Reading"),
            new SchemaCard.Column("limit_value", "double precision", "Limit"),
            new SchemaCard.Column("limit-value", "numeric", "Limit, hyphenated"),
            new SchemaCard.Column("operator_name", "text", "Operator"),
            new SchemaCard.Column("reviewer_name", "character varying", "Reviewer"),
            new SchemaCard.Column("duration", "interval", "Duration"),
            new SchemaCard.Column("duration_max", "interval", "Duration max")));

    // --- the grammar ------------------------------------------------------------------------------------------

    @Test
    void aBracedNameIsAColumnOperand() {
        ViewFilter f = ViewFilter.parseProcessCondition("measured_at < {calib_valid_from}");
        assertThat(f.column()).isEqualTo("measured_at");
        assertThat(f.op()).isEqualTo("<");
        assertThat(f.operandColumn()).isEqualTo("calib_valid_from");
    }

    @Test
    void aBareNameStaysALiteral_evenWhenItEqualsAColumn() {
        ViewFilter f = ViewFilter.parseProcessCondition("operator_name=reviewer_name");
        assertThat(f.operandColumn()).isNull();
        assertThat(f.value()).isEqualTo("reviewer_name");
    }

    @Test
    void theViewGrammarIsUntouched() {
        assertThat(ViewFilter.parse("operator_name={reviewer_name}").value()).isEqualTo("{reviewer_name}");
        assertThatThrownBy(() -> ViewFilter.parse("measured_at<{calib_valid_from}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number or ISO date");
    }

    @Test
    void containsNeverTakesAColumnOperand() {
        assertThatThrownBy(() -> ViewFilter.parseProcessCondition("operator_name~{reviewer_name}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{reviewer_name}")
                .hasMessageContaining("= or an ordering operator");
    }

    @Test
    void anOrderingLiteralIsStillHeldToANumberOrADate() {
        assertThatThrownBy(() -> ViewFilter.parseProcessCondition("reading>abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number or ISO date");
        assertThat(ViewFilter.parseProcessCondition("reading>10").operandColumn()).isNull();
    }

    @Test
    void aBracedNameMayCarryAnyCharacterButBraces() {
        assertThat(ViewFilter.parseProcessCondition("reading>{limit-value}").operandColumn()).isEqualTo("limit-value");
        assertThat(ViewFilter.parseProcessCondition("reading>{Limit Value}").operandColumn()).isEqualTo("Limit Value");
        // nothing between the braces, or a brace inside: a literal, as before
        assertThat(ViewFilter.parseProcessCondition("operator_name={}").operandColumn()).isNull();
        assertThat(ViewFilter.parseProcessCondition("operator_name={a{b}").operandColumn()).isNull();
    }

    @Test
    void spacesInsideTheBracesAreNotPartOfTheName() {
        ViewFilter f = ViewFilter.parseProcessCondition("measured_at > { calib_valid_until }");
        assertThat(f.operandColumn()).isEqualTo("calib_valid_until");
        assertThat(ProcessConditionResolver.resolve(List.of("measured_at > { calib_valid_until }"), table)).hasSize(1);
        assertThat(ViewFilter.parseProcessCondition("reading>{ Limit Value }").operandColumn()).isEqualTo("Limit Value");
        assertThat(ViewFilter.parseProcessCondition("operator_name={  }").operandColumn()).isNull();   // nothing named: a literal
    }

    @Test
    void aHyphenatedRightHandColumnIsResolvedAndEvaluated() {
        assertThat(ProcessConditionResolver.resolve(List.of("reading>={limit-value}"), table)).hasSize(1);
        assertThat(matches(row("reading", new BigDecimal("7"), "limit-value", new BigDecimal("5")),
                "reading>{limit-value}")).isTrue();
    }

    @Test
    void anEqualityOnAnUnknownHyphenatedColumnIsRefused_notTakenForALiteral() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("operator_name={reviewer-name}"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer-name").hasMessageContaining("connector.t_result");
    }

    // --- apply-time honesty -----------------------------------------------------------------------------------

    @Test
    void resolvesTwoTemporalColumns() {
        List<ViewFilter> conds = ProcessConditionResolver.resolve(
                List.of("measured_at<{calib_valid_from}", "measured_at>{calib_valid_until}"), table);
        assertThat(conds).extracting(ViewFilter::operandColumn)
                .containsExactly("calib_valid_from", "calib_valid_until");
    }

    @Test
    void resolvesNumbersAcrossIntegerAndFloatKinds_andTextAgainstText() {
        assertThat(ProcessConditionResolver.resolve(
                List.of("reading>={limit_value}", "operator_name={reviewer_name}"), table)).hasSize(2);
    }

    @Test
    void refusesAnUnknownRightHandColumn_namingIt() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("measured_at<{valid_from}"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_from").hasMessageContaining("connector.t_result");
    }

    @Test
    void refusesColumnsOfDifferentKinds() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("measured_at<{reading}"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("measured_at").hasMessageContaining("reading")
                .hasMessageContaining("date").hasMessageContaining("number");
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("operator_name={reading}"), table))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAColumnComparedWithItself() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("reading>={reading}"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reading").hasMessageContaining("itself");
    }

    @Test
    void refusesAnOrderingOnAKindThatCannotOrder() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("duration<{duration_max}"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duration").hasMessageContaining("interval");
    }

    @Test
    void anOpaqueKindStillTakesEqualityAgainstTheSameType() {
        assertThat(ProcessConditionResolver.resolve(List.of("duration={duration_max}"), table)).hasSize(1);
    }

    // --- evaluation -------------------------------------------------------------------------------------------

    private static boolean matches(Map<String, Object> row, String when) {
        return ProcessRuleEvaluator.matches(row, List.of(ViewFilter.parseProcessCondition(when)));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void comparesTwoTimestampsAsInstants() {
        Map<String, Object> r = row("measured_at", Timestamp.valueOf("2026-03-01 10:00:00"),
                                    "calib_valid_until", Timestamp.valueOf("2026-02-01 00:00:00"));
        assertThat(matches(r, "measured_at>{calib_valid_until}")).isTrue();
        assertThat(matches(r, "measured_at<{calib_valid_until}")).isFalse();
        assertThat(matches(r, "measured_at>={calib_valid_until}")).isTrue();
        assertThat(matches(r, "measured_at<={calib_valid_until}")).isFalse();
    }

    @Test
    void aDateAgainstATimestamp_isComparedAsAnInstant() {
        Map<String, Object> r = row("measured_at", Timestamp.valueOf("2026-03-02 12:00:00"),
                                    "calib_valid_from", java.sql.Date.valueOf("2026-03-01"));
        assertThat(matches(r, "measured_at>{calib_valid_from}")).isTrue();
        assertThat(matches(r, "measured_at<{calib_valid_from}")).isFalse();
    }

    @Test
    void comparesNumbersByValue_acrossNativeTypes() {
        Map<String, Object> r = row("reading", new BigDecimal("5.00"), "limit_value", 5.0d);
        assertThat(matches(r, "reading={limit_value}")).isTrue();
        assertThat(matches(r, "reading>={limit_value}")).isTrue();
        assertThat(matches(r, "reading>{limit_value}")).isFalse();
        assertThat(matches(row("reading", 7, "limit_value", 5.0d), "reading>{limit_value}")).isTrue();
    }

    @Test
    void comparesDatesLandedAsText() {
        Map<String, Object> r = row("measured_at", "2026-03-01", "calib_valid_until", "2026-02-01");
        assertThat(matches(r, "measured_at>{calib_valid_until}")).isTrue();
        assertThat(matches(r, "measured_at<{calib_valid_until}")).isFalse();
    }

    @Test
    void equalityOfTwoTextColumnsIsExactText() {
        assertThat(matches(row("operator_name", "Ada", "reviewer_name", "Ada"), "operator_name={reviewer_name}")).isTrue();
        assertThat(matches(row("operator_name", "Ada", "reviewer_name", "ada"), "operator_name={reviewer_name}")).isFalse();
    }

    @Test
    void equalityOfTwoTextCells_isExactText_notNumberOrInstant() {
        assertThat(matches(row("operator_name", "01", "reviewer_name", "1"), "operator_name={reviewer_name}")).isFalse();
        assertThat(matches(row("operator_name", "1", "reviewer_name", "1.0"), "operator_name={reviewer_name}")).isFalse();
        assertThat(matches(row("measured_at", "2026-03-01T00:00:00Z", "calib_valid_until", "2026-03-01"),
                "measured_at={calib_valid_until}")).isFalse();
        assertThat(matches(row("operator_name", "01", "reviewer_name", "01"), "operator_name={reviewer_name}")).isTrue();
    }

    @Test
    void orderingOfTwoTextCells_stillReadsNumbersAndDates() {
        assertThat(matches(row("operator_name", "01", "reviewer_name", "1"), "operator_name>={reviewer_name}")).isTrue();
        assertThat(matches(row("operator_name", "01", "reviewer_name", "1"), "operator_name>{reviewer_name}")).isFalse();
    }

    @Test
    void equalityByValueSurvivesForNativeNumbers_andForAStringAgainstANativeNumber() {
        assertThat(matches(row("reading", new BigDecimal("5.00"), "limit_value", 5.0d), "reading={limit_value}")).isTrue();
        assertThat(matches(row("reading", "5.00", "limit_value", 5.0d), "reading={limit_value}")).isTrue();
    }

    @Test
    void aNullOnEitherSideNeverMatches() {
        Map<String, Object> rightNull = row("measured_at", Timestamp.valueOf("2026-03-01 10:00:00"), "calib_valid_until", null);
        Map<String, Object> leftNull = row("measured_at", null, "calib_valid_until", Timestamp.valueOf("2026-02-01 00:00:00"));
        Map<String, Object> missing = row("measured_at", Timestamp.valueOf("2026-03-01 10:00:00"));
        for (String when : List.of("measured_at>{calib_valid_until}", "measured_at<{calib_valid_until}",
                                   "measured_at={calib_valid_until}", "measured_at>={calib_valid_until}")) {
            assertThat(matches(rightNull, when)).as(when + " right null").isFalse();
            assertThat(matches(leftNull, when)).as(when + " left null").isFalse();
            assertThat(matches(missing, when)).as(when + " right absent").isFalse();
        }
    }

    @Test
    void anIncomparablePairNeverMatches() {
        assertThat(matches(row("measured_at", Timestamp.valueOf("2026-03-01 10:00:00"), "reading", new BigDecimal("3")),
                "measured_at>{reading}")).isFalse();
        assertThat(matches(row("measured_at", "not a date", "calib_valid_until", "2026-02-01"),
                "measured_at>{calib_valid_until}")).isFalse();
        assertThat(matches(row("reading", Double.NaN, "limit_value", 5.0d), "reading>{limit_value}")).isFalse();
    }

    @Test
    void aColumnConditionAndALiteralOneAreAnded() {
        List<ViewFilter> conds = List.of(
                ViewFilter.parseProcessCondition("measured_at>{calib_valid_until}"),
                ViewFilter.parseProcessCondition("operator_name=Ada"));
        Map<String, Object> r = row("measured_at", Timestamp.valueOf("2026-03-01 10:00:00"),
                                    "calib_valid_until", Timestamp.valueOf("2026-02-01 00:00:00"), "operator_name", "Ada");
        assertThat(ProcessRuleEvaluator.matches(r, conds)).isTrue();
        r.put("operator_name", "Bob");
        assertThat(ProcessRuleEvaluator.matches(r, conds)).isFalse();
    }
}

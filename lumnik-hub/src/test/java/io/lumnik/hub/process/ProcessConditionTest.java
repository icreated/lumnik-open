package io.lumnik.hub.process;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.view.ViewFilter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessConditionTest {

    private final SchemaCard.Table table = new SchemaCard.Table("connector.t_cust_csv", "Customers", List.of(
            new SchemaCard.Column("code", "text", "Code"),
            new SchemaCard.Column("name", "text", "Name"),
            new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                    List.of("GOLD", "SILVER", "BRONZE"))));

    @Test
    void resolvesValidConditions() {
        List<ViewFilter> conds = ProcessConditionResolver.resolve(
                List.of("segment=GOLD", "name~mar"), table);
        assertThat(conds).containsExactly(
                new ViewFilter("segment", "=", "GOLD"),
                new ViewFilter("name", "~", "mar"));
    }

    @Test
    void refusesAnUnknownColumn() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("city=Lyon"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city").hasMessageContaining("connector.t_cust_csv");
    }

    @Test
    void refusesAnOutOfDomainEqualityValue_withTheAllowedList() {
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("segment=PLATINUM"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATINUM").hasMessageContaining("GOLD, SILVER, BRONZE");
    }

    @Test
    void containsIsExemptFromTheDomainCheck() {
        assertThat(ProcessConditionResolver.resolve(List.of("segment~GO"), table)).hasSize(1);
    }

    @Test
    void evaluatesEqualityContainsAndNulls() {
        List<ViewFilter> conds = List.of(new ViewFilter("segment", "=", "GOLD"),
                                         new ViewFilter("name", "~", "mar"));
        assertThat(ProcessRuleEvaluator.matches(Map.of("segment", "GOLD", "name", "Marie"), conds)).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("segment", "SILVER", "name", "Marie"), conds)).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("segment", "GOLD", "name", "Bob"), conds)).isFalse();
        Map<String,Object> nullName = new HashMap<>();
        nullName.put("segment", "GOLD");
        nullName.put("name", null);
        assertThat(ProcessRuleEvaluator.matches(nullName, conds)).isFalse();   // null never matches
    }

    @Test
    void containsIsCaseInsensitive_andNonStringValuesCompareAsText() {
        assertThat(ProcessRuleEvaluator.matches(Map.of("name", "MARIE A."),
                List.of(new ViewFilter("name", "~", "marie")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("code", 42),
                List.of(new ViewFilter("code", "=", "42")))).isTrue();
    }

    @Test
    void numericThresholds_compareTyped_acrossRowValueKinds() {
        List<ViewFilter> over100 = List.of(ViewFilter.parse("amount>100"));
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", 150), over100)).isTrue();          // Number
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", new BigDecimal("150.5")), over100)).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", "150"), over100)).isTrue();        // parseable String
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", "9"), over100)).isFalse();         // 9 < 100 (typed, NOT text!)
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", "n/a"), over100)).isFalse();       // dirty → no match
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", "1e5"), over100)).isFalse();       // scientific = dirty in BOTH worlds
        Map<String, Object> nullRow = new HashMap<>();
        nullRow.put("amount", null);
        assertThat(ProcessRuleEvaluator.matches(nullRow, over100)).isFalse();                       // null never matches
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", 100), List.of(ViewFilter.parse("amount>=100")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", 100), over100)).isFalse();         // strict
    }

    @Test
    void temporalThresholds_compareTyped() {
        List<ViewFilter> before = List.of(ViewFilter.parse("due<2026-08-01"));
        assertThat(ProcessRuleEvaluator.matches(
            Map.of("due", Timestamp.from(Instant.parse("2026-07-01T00:00:00Z"))), before)).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("due", "2026-07-01"), before)).isTrue();     // ISO String
        assertThat(ProcessRuleEvaluator.matches(Map.of("due", "2026-09-01"), before)).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("due", "soon"), before)).isFalse();          // dirty → no match
        assertThat(ProcessRuleEvaluator.matches(
            Map.of("due", Instant.parse("2026-07-01T00:00:00Z")),
            List.of(ViewFilter.parse("due<2026-08-01")))).isTrue();                                 // Instant-direct
        assertThat(ProcessRuleEvaluator.matches(
            Map.of("due", new Date(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli())),
            List.of(ViewFilter.parse("due<2026-08-01")))).isTrue();                                 // bare java.util.Date
    }

    @Test
    void nonFiniteNumbersAreIncomparable_neverThrow() {
        List<ViewFilter> over100 = List.of(ViewFilter.parse("amount>100"));
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", Double.NaN), over100)).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", Double.POSITIVE_INFINITY), over100)).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", Double.NEGATIVE_INFINITY), over100)).isFalse();
    }

    @Test
    void aRawOrderingFilterWithANonOrderedValueNeverMatches_neverThrows() {
        // built outside parse(): ordering op, operandType()==null. Use a row value that PARSES
        // as temporal so left != null — without the guard, ordered() falls into compareTemporal
        // and hits left.compareTo(parseTemporal("abc")==null) → NPE. The guard makes it no-match.
        ViewFilter bad = new ViewFilter("due", ">", "abc");
        assertThat(ProcessRuleEvaluator.matches(
            Map.of("due", Instant.parse("2026-07-01T00:00:00Z")),
            List.of(bad))).isFalse();
    }

    @Test
    void scientificMagnitudeNativeNumbersAreIncomparable_matchingTheSqlGuard() {
        List<ViewFilter> over100 = List.of(ViewFilter.parse("amount>100"));
        // a native Double whose toString() is scientific ("1.0E20") is excluded in-memory just as
        // the SQL NUMERIC_SHAPE guard excludes the text "1e20" — the two worlds stay consistent
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", 1.0e20), over100)).isFalse();
        // a plain-form magnitude still compares (BigDecimal from a numeric column, plain toString)
        assertThat(ProcessRuleEvaluator.matches(Map.of("amount", new BigDecimal("150")), over100)).isTrue();
    }

    @Test
    void refusesAMessagePlaceholderThatIsNotAColumn() {
        SchemaCard.Table t = new SchemaCard.Table("connector.t_cust_csv", "Cust", List.of(
            new SchemaCard.Column("cname", "text", "Nom"),
            new SchemaCard.Column("ville", "text", "Ville")));
        assertThatThrownBy(() ->
                ProcessConditionResolver.validateMessage("Nouveau client {cname} en {segmen}", t))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("message references {segmen} — not a column of connector.t_cust_csv");
        // valid + placeholder-free both pass silently
        ProcessConditionResolver.validateMessage("Nouveau client {cname} à {ville}", t);
        ProcessConditionResolver.validateMessage("no placeholders", t);
    }
}

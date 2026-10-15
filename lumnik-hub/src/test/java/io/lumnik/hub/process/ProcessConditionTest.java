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
    void aNativeNumberIsComparedAsANumber_whateverItsJavaTextLooksLike() {
        // #795: Double.toString() is scientific for small and large magnitudes (2.0E-5, 1.0E20). A native
        // Number has no dirty form, so it is compared by value; the strict text shape is for Strings only.
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 0.00002), List.of(ViewFilter.parse("x>1e-5")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 0.00002), List.of(ViewFilter.parse("x>0.00003")))).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 1.0e20), List.of(ViewFilter.parse("x>100")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 1.0e20), List.of(ViewFilter.parse("x<1e21")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 2.0e-5f), List.of(ViewFilter.parse("x>=0.00002")))).isTrue();   // Float
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", new BigDecimal("1E+3")), List.of(ViewFilter.parse("x>999")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", new BigDecimal("150")), List.of(ViewFilter.parse("x>100")))).isTrue();
    }

    @Test
    void aScientificStringStaysIncomparable_andNonFiniteNumbersToo() {
        // the strict text shape still guards STRING values (a dirty CSV cell must not match)
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", "1e5"), List.of(ViewFilter.parse("x>100")))).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", "2.0E-5"), List.of(ViewFilter.parse("x>1e-6")))).isFalse();
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThat(ProcessRuleEvaluator.matches(Map.of("x", bad), List.of(ViewFilter.parse("x>=0")))).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", Float.NaN), List.of(ViewFilter.parse("x>=0")))).isFalse();
    }

    @Test
    void equalityOnANativeNumberIsNumeric_whenTheOperandIsANumber() {
        // text equality made Double 5.0 never equal "5", and BigDecimal 5.00 never equal "5"
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 5.0), List.of(ViewFilter.parse("x=5")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", new BigDecimal("5.00")), List.of(ViewFilter.parse("x=5")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 5), List.of(ViewFilter.parse("x=5")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 0.00002), List.of(ViewFilter.parse("x=0.00002")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 1.0e-5), List.of(ViewFilter.parse("x=1e-5")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 0.1f), List.of(ViewFilter.parse("x=0.1")))).isTrue();   // Float: its shortest text, not its binary expansion
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 5.5), List.of(ViewFilter.parse("x=5")))).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", Double.NaN), List.of(ViewFilter.parse("x=5")))).isFalse();
        // a String is still an exact text comparison, and a non-numeric operand never turns a Number numeric
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", "5.0"), List.of(ViewFilter.parse("x=5")))).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", "GOLD"), List.of(ViewFilter.parse("x=GOLD")))).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 5), List.of(ViewFilter.parse("x=abc")))).isFalse();
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

    @Test
    void refusesAQuotedValue_theWhenConditionThatCouldNeverBeTrue() {
        // #503, the ticket's own shape. `segment='GOLD'` was already refused here — but for the
        // wrong reason (out of domain), and a column with no declared domain had nothing to say:
        // the condition was accepted, compared against the seven characters 'Marie', and the
        // process sat enabled and silent for ever. One grammar, so one refusal for both kinds.
        assertThatThrownBy(() -> ProcessConditionResolver.resolve(List.of("name='Marie'"), table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name=Marie");
    }

    @Test
    void aScientificOperandEvaluatesLikeItsPlainForm() {
        // #792: the shared operand grammar accepts 1e-5; #795: a native Double 0.00002 (text 2.0E-5) is compared by value
        List<ViewFilter> gt = List.of(ViewFilter.parse("x>1e-5"));
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", 0.00002), gt)).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", new BigDecimal("0.00002")), gt)).isTrue();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", new BigDecimal("0.000001")), gt)).isFalse();
        assertThat(ProcessRuleEvaluator.matches(Map.of("x", "5"), List.of(ViewFilter.parse("x<6.022e23")))).isTrue();
    }
}

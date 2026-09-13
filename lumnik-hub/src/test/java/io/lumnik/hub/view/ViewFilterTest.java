package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.sql.TemporalText;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewFilterTest {

    private final SchemaCard.Table table = new SchemaCard.Table("connector.v_customer", "Customer", List.of(
            new SchemaCard.Column("customer_code", "text", "Code"),
            new SchemaCard.Column("name", "text", "Name"),
            new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                    List.of("GOLD", "SILVER", "BRONZE"))));

    private final ResolvedView rv = new ResolvedView("Clients", "connector.v_customer", List.of(
            new ResolvedView.Column("customer_code", "Code", "text"),
            new ResolvedView.Column("name", "Name", "text"),
            new ResolvedView.Column("segment", "Segment", "text")));

    @Test
    void parsesEqualityAndContains() {
        assertThat(ViewFilter.parse("segment=GOLD")).isEqualTo(new ViewFilter("segment", "=", "GOLD"));
        assertThat(ViewFilter.parse("name~dup")).isEqualTo(new ViewFilter("name", "~", "dup"));
    }

    @Test
    void firstOperatorWins() {
        // a value may contain the other operator character
        assertThat(ViewFilter.parse("name=a~b")).isEqualTo(new ViewFilter("name", "=", "a~b"));
        assertThat(ViewFilter.parse("name~a=b")).isEqualTo(new ViewFilter("name", "~", "a=b"));
    }

    @Test
    void stripsSurroundingWhitespaceFromColumnAndValue() {
        assertThat(ViewFilter.parse("  segment = GOLD ")).isEqualTo(new ViewFilter("segment", "=", "GOLD"));
        assertThat(ViewFilter.parse("name ~ ali ")).isEqualTo(new ViewFilter("name", "~", "ali"));
        // a padded but valid value still passes the domain check after stripping
        assertThat(ViewFilter.resolveAll(List.of("segment= GOLD "), rv, table))
                .containsExactly(new ViewFilter("segment", "=", "GOLD"));
        // whitespace-only value is malformed
        assertThatThrownBy(() -> ViewFilter.parse("segment=   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAMalformedFilter() {
        assertThatThrownBy(() -> ViewFilter.parse("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("col=value").hasMessageContaining("col~text");
        assertThatThrownBy(() -> ViewFilter.parse("=GOLD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ViewFilter.parse("segment=")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAColumnTheViewDoesNotShow() {
        assertThatThrownBy(() -> ViewFilter.resolveAll(List.of("city=Lyon"), rv, table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city")
                .hasMessageContaining("spec.columns");
    }

    @Test
    void refusesAnOutOfDomainEqualityValue_withTheAllowedList() {
        assertThatThrownBy(() -> ViewFilter.resolveAll(List.of("segment=PLATINUM"), rv, table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATINUM")
                .hasMessageContaining("GOLD, SILVER, BRONZE");
    }

    @Test
    void containsIsExemptFromTheDomainCheck() {
        assertThat(ViewFilter.resolveAll(List.of("segment~GO"), rv, table))
                .containsExactly(new ViewFilter("segment", "~", "GO"));
    }

    @Test
    void resolvesAValidListAndNullMeansNone() {
        assertThat(ViewFilter.resolveAll(List.of("segment=GOLD", "name~dup"), rv, table)).hasSize(2);
        assertThat(ViewFilter.resolveAll(null, rv, table)).isEmpty();
    }

    @Test
    void parsesTheFourOrderingOperators() {
        assertThat(ViewFilter.parse("montant>100")).isEqualTo(new ViewFilter("montant", ">", "100"));
        assertThat(ViewFilter.parse("montant<100")).isEqualTo(new ViewFilter("montant", "<", "100"));
        assertThat(ViewFilter.parse("montant>=100")).isEqualTo(new ViewFilter("montant", ">=", "100"));
        assertThat(ViewFilter.parse("montant<=100")).isEqualTo(new ViewFilter("montant", "<=", "100"));
    }

    @Test
    void twoCharOperatorsNeverSplitOnTheirSecondChar() {
        // the trap: a naive first-of scan sees the '=' of '>=' and splits column "montant>"
        assertThat(ViewFilter.parse("montant>=10").column()).isEqualTo("montant");
        assertThat(ViewFilter.parse("montant>=10").op()).isEqualTo(">=");
        assertThat(ViewFilter.parse("montant<=10").op()).isEqualTo("<=");
    }

    @Test
    void refusesANonOrderedValueOnAnOrderingOperator() {
        assertThatThrownBy(() -> ViewFilter.parse("segment>abc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'abc' is not a number or ISO date — > needs an ordered value");
        assertThatThrownBy(() -> ViewFilter.parse("montant>=1e5"))   // scientific notation is dirty
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ViewFilter.parse("d<2026-02-30"))   // shape ok, not a real date
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesTheOperandType() {
        assertThat(ViewFilter.parse("montant>100").operandType()).isEqualTo(ViewFilter.OperandType.NUMERIC);
        assertThat(ViewFilter.parse("montant> -3.5 ").operandType()).isEqualTo(ViewFilter.OperandType.NUMERIC);
        assertThat(ViewFilter.parse("echeance<2026-08-01").operandType()).isEqualTo(ViewFilter.OperandType.TEMPORAL);
        assertThat(ViewFilter.parse("ts>=2026-08-01T12:00:00Z").operandType()).isEqualTo(ViewFilter.OperandType.TEMPORAL);
        assertThat(ViewFilter.parse("segment=GOLD").operandType()).isNull();
        assertThat(ViewFilter.parse("name~mar").operandType()).isNull();
    }

    @Test
    void temporalShapeMatchesOnlyWellFormedIsoTimestamps() {
        for (String ok : new String[]{"2026-08-01", "2026-08-01T12:00:00Z",
                "2026-07-05T10:00:00.123456Z", "2026-08-01 10:04:56.019386", "2026-07-05 10:04:56.019386+00"})
            assertThat(ok.matches(ViewFilter.TEMPORAL_SHAPE)).as(ok).isTrue();
        for (String bad : new String[]{"2026-08-01 soon", "2026-08-01 25:00", "2026-08-01 12:99", "0000-00-00", "2026-13-01"})
            assertThat(bad.matches(ViewFilter.TEMPORAL_SHAPE)).as(bad).isFalse();
    }

    @Test
    void everyTemporalShapeMatchIsAlsoParseable_noRegexParserDrift() {
        // the SQL guard (regex) and the in-memory parser MUST agree on comparability
        for (String ok : new String[]{"2026-08-01", "2026-08-01T12:00:00Z",
                "2026-07-05T10:00:00.123456Z", "2026-08-01 10:04:56.019386",
                "2026-07-05 10:04:56.019386+00", "2026-07-05 10:04:56.019386+05:30",
                "2026-08-01T10:04:56-05"}) {   // T-separated (no space) + short offset: the pad must fire on the T, not the date
            assertThat(ok.matches(ViewFilter.TEMPORAL_SHAPE)).as("shape: " + ok).isTrue();
            assertThat(TemporalText.parse(ok)).as("parse: " + ok).isNotNull();
        }
    }

    @Test
    void orderingOperatorsAreExemptFromTheAllowedValuesDomainCheck() {
        // the class fixtures: `table` declares segment allowedValues [GOLD, SILVER, BRONZE],
        // `rv` shows the segment column. '=' out-of-domain refuses (the existing test above);
        // an ordering operator passes: a threshold is not a membership assertion.
        assertThat(ViewFilter.resolveAll(List.of("segment>100"), rv, table))
                .containsExactly(new ViewFilter("segment", ">", "100"));
    }

    // ── Shedding the model's SQL quotes (#483) ───────────────────────────────────
    // Only a generated filter goes through this: a hand-written manifest means what it writes.

    @Test
    void shedsMatchingOuterQuotesFromTheValue() {
        assertThat(ViewFilter.withUnquotedValue("segment='GOLD'")).isEqualTo("segment=GOLD");
        assertThat(ViewFilter.withUnquotedValue("segment=\"GOLD\"")).isEqualTo("segment=GOLD");
        assertThat(ViewFilter.withUnquotedValue("city ~ 'Lyon'")).isEqualTo("city ~ Lyon");
        // An ordered operand too: a quoted number is not a number, and the parse refused it.
        assertThat(ViewFilter.parse(ViewFilter.withUnquotedValue("amount>'500'")))
            .isEqualTo(new ViewFilter("amount", ">", "500"));
    }

    @Test
    void leavesEverythingElseWhole() {
        assertThat(ViewFilter.withUnquotedValue("segment=GOLD")).isEqualTo("segment=GOLD");
        assertThat(ViewFilter.withUnquotedValue("name='Acme")).isEqualTo("name='Acme");   // unmatched
        assertThat(ViewFilter.withUnquotedValue("name=\"Acme'")).isEqualTo("name=\"Acme'"); // mismatched
        // SQL escaping ('') is a shape the view grammar does not model — leave it whole for the
        // gate to refuse, rather than half-decode it into a value nobody wrote.
        assertThat(ViewFilter.withUnquotedValue("name='O''Brien'")).isEqualTo("name='O''Brien'");
        // A quote that is the value, and a value that merely contains one.
        assertThat(ViewFilter.withUnquotedValue("name='")).isEqualTo("name='");
        assertThat(ViewFilter.withUnquotedValue("name=l'ecole")).isEqualTo("name=l'ecole");
        // No operator at all: not this method's error to raise.
        assertThat(ViewFilter.withUnquotedValue("garbage")).isEqualTo("garbage");
    }

    // ── A value is a bare literal: a human's quotes are REFUSED (#503) ───────────
    // The other door of #483. A model echoing SQL syntax has its quotes shed above; a human
    // writing a manifest means what they write, so the honest answer is a refusal — never a
    // silent rewrite, and never the zero-row table a quoted value used to produce in silence.

    @Test
    void refusesAQuotedValue() {
        assertThatThrownBy(() -> ViewFilter.parse("city='Lyon'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city=Lyon");
        assertThatThrownBy(() -> ViewFilter.parse("city=\"Lyon\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city=Lyon");
        // ~ too: a quoted search matches nothing just as silently as a quoted equality.
        assertThatThrownBy(() -> ViewFilter.parse("name~'ali'"))
                .isInstanceOf(IllegalArgumentException.class);
        // An ordered operand: '500' is not a number, and this refusal is the clearer of the two.
        assertThatThrownBy(() -> ViewFilter.parse("amount>'500'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>500");
        // SQL escaping is a shape this grammar does not model: refused whole, never half-decoded.
        assertThatThrownBy(() -> ViewFilter.parse("name='O''Brien'"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ViewFilter.parse("name=''"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesAValueThatMerelyCarriesAQuoteAlone() {
        assertThat(ViewFilter.parse("name=l'ecole")).isEqualTo(new ViewFilter("name", "=", "l'ecole"));
        assertThat(ViewFilter.parse("name='Acme")).isEqualTo(new ViewFilter("name", "=", "'Acme"));
        assertThat(ViewFilter.parse("name=\"Acme'")).isEqualTo(new ViewFilter("name", "=", "\"Acme'"));
        assertThat(ViewFilter.parse("name='")).isEqualTo(new ViewFilter("name", "=", "'"));
    }
}

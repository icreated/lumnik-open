package io.lumnik.hub.proposal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DerivationTest {

    private static Dossier.Condition condition(String backedBy) {
        return new Dossier.Condition("C1", "status-column-exists", "workflow-data-truth", "x", backedBy, "confirmed", null, null);
    }

    private static Map<String, Dossier.Answer> answers(String value) {
        return Map.of("has-status", new Dossier.Answer("has-status", "yes-no-unknown", "q", value, null, "prospect"));
    }

    /** The site's table (contract section 2) — every row. */
    @ParameterizedTest(name = "answer {0} -> {1}")
    @CsvSource({"yes,unverified", "no,unverified", "unknown,missing"})
    void a_given_answer_derives_its_check(String value, String expected) {
        assertThat(Derivation.check(condition("has-status"), answers(value))).isEqualTo(expected);
    }

    @Test
    void an_unanswered_question_is_missing() {
        assertThat(Derivation.check(condition("has-status"), answers(null))).isEqualTo("missing");
    }

    @Test
    void a_condition_no_question_asks_is_missing() {
        assertThat(Derivation.check(condition(null), Map.of())).isEqualTo("missing");
    }

    @Test
    void recompute_refuses_to_believe_a_carried_outcome_and_keeps_its_evidence() {
        Dossier carried = DossierReader.read(DossierReaderTest.fixture("valid-recheck/confirmed-with-evidence.md"));
        List<Dossier.Evidence> before = carried.leads().get(0).conditions().get(0).evidence();

        Dossier again = Derivation.recompute(carried);

        Dossier.Condition c1 = again.leads().get(0).conditions().get(0);
        assertThat(c1.check()).isEqualTo("unverified");   // answer "yes": to be checked, no more
        assertThat(c1.evidence()).isEqualTo(before);      // history kept, believed by nobody
        assertThat(again.leads().get(0).conditions()).allSatisfy(
            c -> assertThat(c.check()).isIn("unverified", "missing"));
    }

    @Test
    void a_carried_refutation_over_an_unknown_answer_comes_back_missing() {
        Dossier carried = DossierReader.read(DossierReaderTest.fixture("valid-recheck/refuted-over-unknown.md"));

        Dossier again = Derivation.recompute(carried);

        assertThat(again.leads().get(0).conditions()).allSatisfy(
            c -> assertThat(c.check()).isIn("unverified", "missing"));
        assertThat(again.carriesEvidence()).isTrue();     // still attached as history
    }
}

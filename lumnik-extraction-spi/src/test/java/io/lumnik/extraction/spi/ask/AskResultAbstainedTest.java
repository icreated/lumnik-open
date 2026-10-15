package io.lumnik.extraction.spi.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The structural half of an abstention (#915): a client or a grader never reads the sentence for it. */
class AskResultAbstainedTest {

    @Test
    void an_answer_is_not_an_abstention_unless_it_says_so() {
        assertThat(new AskResult("a", "", List.of(), 0).abstained()).isFalse();
        assertThat(new AskResult("a", "", List.of(), 0, null, List.of("lifecycle")).abstained()).isFalse();
        assertThat(new AskResult("a", "", List.of(), 0, null, null, null, false).abstained()).isFalse();
    }

    @Test
    void withAbstained_flags_the_result_and_keeps_the_rest() {
        AskResult r = new AskResult("a", "SELECT 1", List.of(), 0, "2026-09-13T00:00:00Z", List.of("lifecycle"))
            .withAbstained();
        assertThat(r.abstained()).isTrue();
        assertThat(r.answer()).isEqualTo("a");
        assertThat(r.sql()).isEqualTo("SELECT 1");
        assertThat(r.otherDoors()).containsExactly("lifecycle");
        assertThat(r.dataAsOf()).isEqualTo("2026-09-13T00:00:00Z");
    }

    @Test
    void withDisputed_keeps_the_abstention() {
        AskResult r = new AskResult("a", "", List.of(), 0).withAbstained()
            .withDisputed(List.of(AskResultDisputedTest.P1008), true);
        assertThat(r.abstained()).isTrue();
    }
}

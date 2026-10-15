package io.lumnik.hub.testsupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StallEvidenceTest {

    @Test
    void tailKeepsTheLastLinesInOrderAndSaysHowManyItDropped() {
        assertThat(StallEvidence.tail("a\nb\nc\nd\ne", 2)).isEqualTo("(3 earlier lines dropped)\nd\ne");
    }

    @Test
    void tailOfAShortTextIsTheTextAndOfNothingSaysSo() {
        assertThat(StallEvidence.tail("a\nb", 5)).isEqualTo("a\nb");
        assertThat(StallEvidence.tail("", 5)).isEqualTo("(no output)");
        assertThat(StallEvidence.tail(null, 5)).isEqualTo("(no output)");
    }

    @Test
    void hostLoadIsANumberOrSaysItIsUnavailable() {
        assertThat(StallEvidence.hostLoad()).matches("\\d+\\.\\d{2}|unavailable");
    }
}

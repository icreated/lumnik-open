package io.lumnik.testkit.search.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecallTest {

    @Test
    void computesRecallAgainstHandComputedValue() {
        // grade >= 2 counts as relevant. Two of the three total relevant judgments show up
        // in the top 4 retrieved: recall@4 = 2/3.
        double recall = Recall.at(4, List.of(3, 0, 2, 1), 3);

        assertThat(recall).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void droppingARelevantHitReducesRecall() {
        double withHit = Recall.at(3, List.of(3, 2, 0), 2);
        double hitDropped = Recall.at(3, List.of(3, 0, 0), 2);

        assertThat(hitDropped).isLessThan(withHit);
    }
}

package io.lumnik.testkit.search.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class NdcgTest {

    @Test
    void computesNdcgAgainstHandComputedValue() {
        // retrieved: grade 3 at rank 1, grade 0 at rank 2, grade 2 at rank 3
        // DCG@3 = 7/1 + 0/log2(3) + 3/log2(4) = 7 + 0 + 1.5 = 8.5
        // ideal grades available for this query: [3, 2]
        // IDCG@3 = 7/1 + 3/log2(3) = 7 + 1.892789... = 8.892789...
        // nDCG@3 = 8.5 / 8.892789... = 0.955831...
        double ndcg = Ndcg.at(3, List.of(3, 0, 2), List.of(3, 2));

        assertThat(ndcg).isCloseTo(0.955831, within(1e-6));
    }

    @Test
    void reducingNdcgWhenALowerGradeHitPrecedesAHigherGradeHit() {
        List<Integer> idealGrades = List.of(3, 2);

        double correctOrder = Ndcg.at(2, List.of(3, 2), idealGrades);
        double invertedOrder = Ndcg.at(2, List.of(2, 3), idealGrades);

        assertThat(invertedOrder).isLessThan(correctOrder);
    }
}

package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RrfTest {

    @Test
    void aSingleBranchKeepsItsOrder() {
        assertThat(Rrf.fuse(List.of(List.of("a", "b", "c")), 10)).containsExactly("a", "b", "c");
    }

    @Test
    void aChunkFoundByBothBranchesAddsItsContributions() {
        // a: 1/61 + 1/62, c: 1/63 + 1/61, b: 1/62
        assertThat(Rrf.fuse(List.of(List.of("a", "b", "c"), List.of("c", "a")), 10)).containsExactly("a", "c", "b");
    }

    @Test
    void anEqualScoreFallsBackToBestBranchRankThenId() {
        // x only in A at rank 1, y only in B at rank 1: equal score, equal best rank → id order.
        assertThat(Rrf.fuse(List.of(List.of("y"), List.of("x")), 10)).containsExactly("x", "y");
    }

    @Test
    void equalSumsFromSwappedRanksAreOrderedByBestRankThenId() {
        // p: rank1 in A + rank2 in B; q: rank2 in A + rank1 in B → identical score, identical best rank → id order.
        assertThat(Rrf.fuse(List.of(List.of("p", "q"), List.of("q", "p")), 10)).containsExactly("p", "q");
    }

    @Test
    void onlyTheFirstFiftyOfEachBranchCount() {
        List<String> branch = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            branch.add(String.format("c%02d", i));
        }
        List<String> fused = Rrf.fuse(List.of(branch), 100);
        assertThat(fused).hasSize(Rrf.BRANCH_DEPTH).doesNotContain("c51", "c60");
    }

    @Test
    void theResultIsCutToTopK() {
        assertThat(Rrf.fuse(List.of(List.of("a", "b", "c", "d")), 2)).containsExactly("a", "b");
    }

    @Test
    void anEmptyBranchContributesNothing() {
        assertThat(Rrf.fuse(List.of(List.of(), List.of("a")), 10)).containsExactly("a");
        assertThat(Rrf.fuse(List.of(List.of(), List.of()), 10)).isEmpty();
    }

    @Test
    void aBranchListingAChunkTwiceIsRejected() {
        assertThatThrownBy(() -> Rrf.fuse(List.of(List.of("a", "a")), 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("twice");
    }

    @Test
    void theFrozenParametersAreTheSpecifiedOnes() {
        assertThat(Rrf.K).isEqualTo(60);
        assertThat(Rrf.BRANCH_DEPTH).isEqualTo(50);
    }
}

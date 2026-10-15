package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ChunkCorpusShapeTest {

    private static final ChunkCorpus CORPUS = ChunkBenchFixtures.loadCorpus().corpus();

    private static long count(QueryClass queryClass) {
        return CORPUS.queries().stream().filter(q -> q.queryClass() == queryClass).count();
    }

    @Test
    void theCorpusIsBigEnoughToMeanSomething() {
        assertThat(CORPUS.chunks().size()).isBetween(200, 260);
        assertThat(CORPUS.schemaVersion()).isEqualTo(3);
    }

    @Test
    void everyQueryClassIsRepresentedAsPlanned() {
        assertThat(count(QueryClass.EXACT_TOKEN)).isEqualTo(20);
        assertThat(count(QueryClass.PARAPHRASE)).isEqualTo(21);
        assertThat(count(QueryClass.MIXED)).isEqualTo(21);
        assertThat(count(QueryClass.ISOLATION)).isEqualTo(9);
    }

    @Test
    void twoTenantsTwoCorporaTwoScopeTagsAreInPlay() {
        assertThat(CORPUS.chunks().stream().map(CorpusChunk::tenantId).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(91001L, 91002L);
        assertThat(CORPUS.chunks().stream().map(CorpusChunk::corpus).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("orders", "support");
        assertThat(CORPUS.chunks().stream().flatMap(c -> c.tags().stream()).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("ventes", "compta");
        assertThat(CORPUS.chunks().stream().map(CorpusChunk::type).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("connector", "workflow");
    }

    @Test
    void everyScoredClassCoversFrenchGermanAndEnglish() {
        for (QueryClass queryClass : Set.of(QueryClass.EXACT_TOKEN, QueryClass.PARAPHRASE, QueryClass.MIXED)) {
            Map<String, Long> byLanguage = CORPUS.queries().stream().filter(q -> q.queryClass() == queryClass)
                .collect(Collectors.groupingBy(CorpusChunkQuery::language, Collectors.counting()));
            assertThat(byLanguage).as(queryClass.label()).containsOnlyKeys("fr", "de", "en");
            assertThat(byLanguage.values()).as(queryClass.label()).allMatch(n -> n >= 5);
        }
    }

    @Test
    void everyScoredQueryHasAnIdealChunkAndAConfounder() {
        for (CorpusChunkQuery q : CORPUS.queries()) {
            if (!q.queryClass().scored()) {
                continue;
            }
            assertThat(q.judgments().values()).as(q.id()).contains(3);
            assertThat(q.judgments().size()).as(q.id()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void everyTextIsASingleLine() {
        CORPUS.chunks().forEach(c -> assertThat(c.text()).as(c.id()).doesNotContain("\t", "\n"));
        CORPUS.queries().forEach(q -> assertThat(q.text()).as(q.id()).doesNotContain("\t", "\n"));
    }

    @Test
    void everyForbiddenChunkOfALeakCaseLivesInTheCorpus() {
        Set<String> ids = CORPUS.chunks().stream().map(CorpusChunk::id).collect(Collectors.toSet());
        CORPUS.queries().stream().flatMap(q -> q.mustBeAbsent().stream()).forEach(id -> assertThat(ids).contains(id));
    }

    @Test
    void aDeniedQueryAndItsControlAskTheSameWordsAndOnlyTheViewerDiffers() {
        // The leak case is the viewer's permission, not the question: the denied query and the control that proves the
        // forbidden chunk is retrievable share their text.
        for (CorpusChunkQuery denied : CORPUS.queries()) {
            for (String forbidden : denied.mustBeAbsent()) {
                assertThat(CORPUS.queries().stream()
                    .filter(c -> forbidden.equals(c.mustRankFirst()))
                    .map(CorpusChunkQuery::text))
                    .as("a control for " + forbidden + " asking the same words as " + denied.id())
                    .contains(denied.text());
            }
        }
    }

    @Test
    void aLeakQueryIsShortSoAFaultInASingleBranchStillReachesTheFusedTopTen() {
        // v1 used whole sentences; a lexical-only fault then left the forbidden chunk below ten chunks present in BOTH
        // branches. Short, specific queries are what make a single-branch leak visible after RRF (spec §3.1).
        CORPUS.queries().stream().filter(q -> q.queryClass() == QueryClass.ISOLATION)
            .forEach(q -> assertThat(q.text().split("\\s+").length).as(q.id()).isLessThanOrEqualTo(10));
    }
}

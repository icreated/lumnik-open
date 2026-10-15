package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.testkit.search.corpus.CorpusValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkCorpusLoaderTest {

    private static final String CHUNKS = """
          - {id: c-a, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "alpha"}
          - {id: c-b, tenantId: 1, corpus: orders, tags: [compta], type: connector, text: "beta"}
          - {id: c-x, tenantId: 2, corpus: orders, tags: [ventes], type: connector, text: "alpha"}
        """;

    private static final String SCORED = """
          - {id: q-1, class: exact-token, language: fr, tenantId: 1, text: "alpha", judgments: {c-a: 3, c-b: 1}}
        """;

    private static final String LEAK_PAIR = """
          - {id: q-denied, class: isolation, language: fr, tenantId: 1, text: "alpha", mustBeAbsent: [c-x]}
          - {id: q-control, class: isolation, language: fr, tenantId: 2, text: "alpha", mustRankFirst: c-x}
        """;

    private static String yaml(String chunks, String queries) {
        return "schemaVersion: 1\nseed: 675\nchunks:\n" + chunks + "queries:\n" + queries;
    }

    @Test
    void aWellFormedCorpusLoads() {
        ChunkCorpus corpus = ChunkCorpusLoader.load(yaml(CHUNKS, SCORED + LEAK_PAIR));

        assertThat(corpus.chunks()).hasSize(3);
        assertThat(corpus.queries()).hasSize(3);
        CorpusChunkQuery q = corpus.queries().get(0);
        assertThat(q.queryClass()).isEqualTo(QueryClass.EXACT_TOKEN);
        assertThat(q.judgments()).containsEntry("c-a", 3).containsEntry("c-b", 1);
        assertThat(q.scopeTags()).isEmpty();
        assertThat(q.corpus()).isNull();
    }

    @Test
    void visibilityFollowsTenantCorpusAndScope() {
        ChunkCorpus corpus = ChunkCorpusLoader.load(yaml(CHUNKS, SCORED + LEAK_PAIR));
        CorpusChunk ventes = corpus.chunks().get(0);
        CorpusChunk compta = corpus.chunks().get(1);
        CorpusChunk otherTenant = corpus.chunks().get(2);

        CorpusChunkQuery unscoped = query(1, null, List.of());
        CorpusChunkQuery scoped = query(1, null, List.of("ventes"));
        CorpusChunkQuery otherCorpus = query(1, "support", List.of());

        assertThat(ventes.visibleTo(unscoped)).isTrue();
        assertThat(compta.visibleTo(unscoped)).isTrue();
        assertThat(otherTenant.visibleTo(unscoped)).isFalse();
        assertThat(ventes.visibleTo(scoped)).isTrue();
        assertThat(compta.visibleTo(scoped)).isFalse();
        assertThat(ventes.visibleTo(otherCorpus)).isFalse();
    }

    private static CorpusChunkQuery query(long tenant, String corpus, List<String> scope) {
        return new CorpusChunkQuery("q", QueryClass.EXACT_TOKEN, "fr", tenant, corpus, scope, "t",
            Map.of(), null, List.of());
    }

    @Test
    void duplicateChunkIdIsRejected() {
        String dup = CHUNKS + "  - {id: c-a, tenantId: 1, corpus: orders, tags: [], type: connector, text: \"again\"}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(dup, SCORED + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("duplicate chunk id: c-a");
    }

    @Test
    void aQueryIdMayNotCollideWithAChunkId() {
        String q = "  - {id: c-a, class: exact-token, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-a: 3}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, q + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("collides with a chunk id");
    }

    @Test
    void aJudgmentOnAChunkTheQueryCannotSeeIsRejected() {
        String q = "  - {id: q-1, class: exact-token, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-x: 3}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, q + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("q-1")
            .hasMessageContaining("not visible");
    }

    @Test
    void aScoredQueryNeedsAGradeTwoOrThreeJudgment() {
        String q = "  - {id: q-1, class: mixed, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-a: 1}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, q + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("grade >= 2");
    }

    @Test
    void aGradeOutsideZeroToThreeIsRejected() {
        String q = "  - {id: q-1, class: mixed, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-a: 4}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, q + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void aForbiddenChunkThatItsOwnQueryCanSeeIsRejected() {
        // c-x belongs to tenant 2; a tenant-2 query listing it as forbidden contradicts itself.
        String pair = """
              - {id: q-denied, class: isolation, language: fr, tenantId: 2, text: "alpha", mustBeAbsent: [c-x]}
              - {id: q-control, class: isolation, language: fr, tenantId: 2, text: "alpha", mustRankFirst: c-x}
            """;
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, SCORED + pair)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("q-denied")
            .hasMessageContaining("is visible to this query");
    }

    @Test
    void aLeakCaseWithoutAControlWouldPassVacuouslyAndIsRejected() {
        String onlyDenied = "  - {id: q-denied, class: isolation, language: fr, tenantId: 1, text: \"alpha\", mustBeAbsent: [c-x]}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, SCORED + onlyDenied)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("vacuously");
    }

    @Test
    void anIsolationQueryMustAssertSomething() {
        String empty = "  - {id: q-iso, class: isolation, language: fr, tenantId: 1, text: \"alpha\"}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, SCORED + LEAK_PAIR + empty)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("q-iso")
            .hasMessageContaining("asserts nothing");
    }

    @Test
    void anUnknownClassLabelIsRejected() {
        String q = "  - {id: q-1, class: bogus, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-a: 3}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(CHUNKS, q + LEAK_PAIR)))
            .hasMessageContaining("unknown query class: bogus");
    }

    @Test
    void everyProblemIsReportedTogether() {
        String dup = CHUNKS + "  - {id: c-a, tenantId: 1, corpus: orders, tags: [], type: connector, text: \"again\"}\n";
        String q = "  - {id: q-1, class: mixed, language: fr, tenantId: 1, text: \"alpha\", judgments: {c-a: 1}}\n";
        assertThatThrownBy(() -> ChunkCorpusLoader.load(yaml(dup, q + LEAK_PAIR)))
            .isInstanceOf(CorpusValidationException.class)
            .hasMessageContaining("duplicate chunk id")
            .hasMessageContaining("grade >= 2");
    }
}

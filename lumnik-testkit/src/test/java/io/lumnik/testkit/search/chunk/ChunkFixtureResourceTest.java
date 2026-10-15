package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the committed fixture in every normal build: texts and vectors cannot drift apart silently. */
class ChunkFixtureResourceTest {

    private static final ChunkCorpus CORPUS = ChunkBenchFixtures.loadCorpus().corpus();
    private static final ChunkFixture.Loaded FIXTURE = ChunkBenchFixtures.loadVectors(CORPUS);

    @Test
    void theManifestRecordsHowTheVectorsWereMade() {
        ChunkFixture.Manifest m = FIXTURE.manifest();
        assertThat(m.model()).isEqualTo("nomic-embed-text");
        assertThat(m.modelDigest()).isNotBlank();
        assertThat(m.ollamaVersion()).isNotBlank();
        assertThat(m.dimension()).isEqualTo(768);
        assertThat(m.normalisation()).isEqualTo("none");
        assertThat(m.embeddingPath()).isNotBlank();
        assertThat(m.regenerateCommand()).contains("ChunkFixtureRegenerationTool");
        assertThat(m.ids()).hasSize(CORPUS.chunks().size() + CORPUS.queries().size());
    }

    @Test
    void everyChunkAndEveryQueryHasAVectorOfTheRecordedDimension() {
        assertThat(FIXTURE.vectors()).hasSize(CORPUS.chunks().size() + CORPUS.queries().size());
        FIXTURE.vectors().forEach((id, v) -> {
            assertThat(v).as(id).hasSize(768);
            double norm = 0;
            for (float x : v) {
                norm += (double) x * x;
            }
            assertThat(norm).as("norm of " + id).isGreaterThan(0.0);
        });
    }

    @Test
    void theVectorsAreRealEmbeddingsAlignedWithTheirIds() {
        // A misordered or garbage fixture would not put a query closer to its ideal chunk than to the average chunk the
        // query can see. This is the cheap guard that vectors and ids are aligned.
        for (CorpusChunkQuery q : CORPUS.queries()) {
            if (!q.queryClass().scored()) {
                continue;
            }
            String ideal = q.judgments().entrySet().stream().filter(e -> e.getValue() == 3).findFirst().orElseThrow().getKey();
            List<CorpusChunk> visible = CORPUS.chunks().stream().filter(c -> c.visibleTo(q)).toList();
            double mean = visible.stream().mapToDouble(c -> cosine(FIXTURE.vectors().get(q.id()), FIXTURE.vectors().get(c.id())))
                .average().orElseThrow();
            double toIdeal = cosine(FIXTURE.vectors().get(q.id()), FIXTURE.vectors().get(ideal));
            assertThat(toIdeal).as(q.id() + " is closer to its ideal chunk than to the average visible one").isGreaterThan(mean);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}

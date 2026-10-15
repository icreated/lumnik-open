package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ChunkFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DIM = 4;

    private static final String YAML = """
        schemaVersion: 1
        seed: 675
        chunks:
          - {id: c-a, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "alpha"}
          - {id: c-b, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: "beta"}
        queries:
          - {id: q-1, class: exact-token, language: fr, tenantId: 1, text: "alpha?", judgments: {c-a: 3}}
        """;

    private static ChunkCorpus corpus() {
        return ChunkCorpusLoader.load(YAML);
    }

    private static List<float[]> vectors() {
        // order = c-a, c-b, q-1
        return List.of(new float[] {1f, 0f, 0f, 0f}, new float[] {0f, 1f, 0f, 0f}, new float[] {0.9f, 0.1f, 0f, 0f});
    }

    private static ChunkFixture.Manifest manifest(ChunkCorpus c, byte[] bytes, int dim) {
        return new ChunkFixture.Manifest(c.schemaVersion(), "nomic-embed-text", "digest", "0.30.6", dim, "none",
            "test", "2026-10-02T00:00:00Z", "cmd", ChunkFixture.textsChecksum(c), ChunkFixture.sha256(bytes),
            ChunkFixture.orderedIds(c));
    }

    private static Function<String, byte[]> reader(ChunkFixture.Manifest m, byte[] bytes) {
        return name -> {
            try {
                return name.equals(ChunkFixture.MANIFEST_RESOURCE) ? JSON.writeValueAsBytes(m) : bytes;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Test
    void idsAreChunksSortedThenQueriesSorted() {
        assertThat(ChunkFixture.orderedIds(corpus())).containsExactly("c-a", "c-b", "q-1");
    }

    @Test
    void encodeIsLittleEndianFloat32() {
        byte[] bytes = ChunkFixture.encode(List.of(new float[] {1f}));
        // 1.0f = 0x3F800000 → little-endian 00 00 80 3F
        assertThat(bytes).containsExactly(0x00, 0x00, (byte) 0x80, 0x3F);
    }

    @Test
    void aMatchingFixtureLoadsEveryVectorById() {
        ChunkCorpus c = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());

        ChunkFixture.Loaded loaded = ChunkFixture.load(reader(manifest(c, bytes, DIM), bytes), c);

        assertThat(loaded.vectors()).containsOnlyKeys("c-a", "c-b", "q-1");
        assertThat(loaded.vectors().get("q-1")).containsExactly(0.9f, 0.1f, 0f, 0f);
        assertThat(loaded.manifest().ollamaVersion()).isEqualTo("0.30.6");
    }

    @Test
    void aChangedTextIsDetected() {
        ChunkCorpus original = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());
        ChunkFixture.Manifest m = manifest(original, bytes, DIM);
        ChunkCorpus edited = ChunkCorpusLoader.load(YAML.replace("\"beta\"", "\"beta, edited\""));

        assertThatThrownBy(() -> ChunkFixture.load(reader(m, bytes), edited))
            .isInstanceOf(ChunkFixture.FixtureIntegrityException.class)
            .hasMessageContaining("texts")
            .hasMessageContaining("NEW corpus version");
    }

    @Test
    void aTamperedVectorByteIsDetected() {
        ChunkCorpus c = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());
        ChunkFixture.Manifest m = manifest(c, bytes, DIM);
        byte[] tampered = bytes.clone();
        tampered[5] ^= 0x01;

        assertThatThrownBy(() -> ChunkFixture.load(reader(m, tampered), c))
            .isInstanceOf(ChunkFixture.FixtureIntegrityException.class)
            .hasMessageContaining("vectors.f32")
            .hasMessageContaining("checksum");
    }

    @Test
    void aCorpusThatGrewARowIsDetectedByItsIds() {
        ChunkCorpus original = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());
        ChunkFixture.Manifest m = manifest(original, bytes, DIM);
        ChunkCorpus grown = ChunkCorpusLoader.load(YAML.replace("queries:",
            "  - {id: c-c, tenantId: 1, corpus: orders, tags: [ventes], type: connector, text: \"gamma\"}\nqueries:"));

        assertThatThrownBy(() -> ChunkFixture.load(reader(m, bytes), grown))
            .isInstanceOf(ChunkFixture.FixtureIntegrityException.class)
            .hasMessageContaining("ids");
    }

    @Test
    void aSchemaVersionMismatchIsDetected() {
        ChunkCorpus c = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());
        ChunkFixture.Manifest m = manifest(c, bytes, DIM);
        ChunkCorpus bumped = ChunkCorpusLoader.load(YAML.replace("schemaVersion: 1", "schemaVersion: 2"));

        assertThatThrownBy(() -> ChunkFixture.load(reader(m, bytes), bumped))
            .isInstanceOf(ChunkFixture.FixtureIntegrityException.class)
            .hasMessageContaining("schemaVersion");
    }

    @Test
    void aDimensionThatDoesNotMatchTheBinaryLengthIsDetected() {
        ChunkCorpus c = corpus();
        byte[] bytes = ChunkFixture.encode(vectors());
        ChunkFixture.Manifest wrongDim = manifest(c, bytes, DIM + 1);

        assertThatThrownBy(() -> ChunkFixture.load(reader(wrongDim, bytes), c))
            .isInstanceOf(ChunkFixture.FixtureIntegrityException.class)
            .hasMessageContaining("length");
    }

    @Test
    void textsWithATabOrNewlineCannotBeCanonicalised() {
        ChunkCorpus c = ChunkCorpusLoader.load(YAML.replace("\"alpha\"", "\"al\\tpha\""));
        assertThatThrownBy(() -> ChunkFixture.textsChecksum(c))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tab or newline");
    }
}

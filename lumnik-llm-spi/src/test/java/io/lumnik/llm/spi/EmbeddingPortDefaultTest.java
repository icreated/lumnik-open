package io.lumnik.llm.spi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingPortDefaultTest {

    @Test void embedSingle_extractsFirstFromBatch() {
        EmbeddingPort fake = new EmbeddingPort() {
            @Override public String name() { return "fake"; }
            @Override public int dimension() { return 3; }
            @Override public List<float[]> embedAll(List<String> texts) {
                assertThat(texts).containsExactly("hi");
                return List.of(new float[]{1.0f, 2.0f, 3.0f});
            }
        };
        float[] vec = fake.embed("hi");
        assertThat(vec).containsExactly(1.0f, 2.0f, 3.0f);
    }
}

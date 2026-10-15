package io.lumnik.testkit.llm;

import io.lumnik.llm.spi.EmbeddingPort;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * CDI mock bean for EmbeddingPort. Satisfies ARC injection point validation in @QuarkusTest — the
 * real impl lives in lumnik-llm-langchain and is not on the test classpath of the modules that
 * consume this testkit. Replaced per-test by {@code @InjectMock} where a test needs real vectors.
 */
@Mock
@ApplicationScoped
@LookupIfProperty(name = "lumnik.ai.enabled", stringValue = "true", lookupIfMissing = true)
public class MockEmbeddingPort implements EmbeddingPort {

    @Override
    public String name() { return "mock"; }

    @Override
    public int dimension() { return 768; }

    @Override
    public float[] embed(String text) { return new float[768]; }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(t -> new float[768]).toList();
    }
}

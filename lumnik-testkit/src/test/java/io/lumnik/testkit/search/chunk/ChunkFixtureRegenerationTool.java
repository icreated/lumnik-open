package io.lumnik.testkit.search.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Regenerates the frozen embeddings. NOT part of any normal build (the name does not match surefire's defaults and the
 * test is gated). Regenerating is a NEW corpus version: bump {@code schemaVersion} in both corpus files, establish a new
 * baseline, and say why in the commit.
 *
 * <p>Run: {@code mvn test -Dtest=ChunkFixtureRegenerationTool -pl lumnik-testkit -am
 * -Dsurefire.failIfNoSpecifiedTests=false -Dlumnik.chunkbench.regenerate=true
 * -Dlumnik.chunkbench.ollamaUrl=http://localhost:11434}. It writes {@code lumnik-testkit/target/chunk-fixture/}; a human
 * copies the reviewed files into the resources directory.
 */
class ChunkFixtureRegenerationTool {

    static final String MODEL = "nomic-embed-text";
    static final String REGENERATE_COMMAND = "mvn test -Dtest=ChunkFixtureRegenerationTool -pl lumnik-testkit -am"
        + " -Dsurefire.failIfNoSpecifiedTests=false -Dlumnik.chunkbench.regenerate=true"
        + " -Dlumnik.chunkbench.ollamaUrl=http://localhost:11434";
    private static final int BATCH = 32;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "lumnik.chunkbench.regenerate", matches = "true")
    void regenerate() throws Exception {
        String ollama = System.getProperty("lumnik.chunkbench.ollamaUrl", "http://localhost:11434");
        ChunkCorpus corpus = ChunkBenchFixtures.loadCorpus().corpus();
        List<String> ids = ChunkFixture.orderedIds(corpus);
        Map<String, String> textById = new HashMap<>();
        corpus.chunks().forEach(c -> textById.put(c.id(), c.text()));
        corpus.queries().forEach(q -> textById.put(q.id(), q.text()));
        HttpClient http = HttpClient.newHttpClient();

        String ollamaVersion = JSON.readTree(get(http, ollama + "/api/version")).path("version").asText();
        String digest = digestOf(http, ollama);

        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + BATCH)).stream().map(textById::get).toList();
            vectors.addAll(embed(http, ollama, batch));
        }
        assertThat(vectors).hasSize(ids.size());
        int dimension = vectors.get(0).length;
        assertThat(vectors).allMatch(v -> v.length == dimension);

        byte[] bytes = ChunkFixture.encode(vectors);
        ChunkFixture.Manifest manifest = new ChunkFixture.Manifest(corpus.schemaVersion(), MODEL, digest, ollamaVersion,
            dimension, "none", "ollama /api/embed, direct call (same model as LangChainEmbeddingPort's default;"
            + " equivalence with the langchain4j client is assumed, not proven)", Instant.now().toString(),
            REGENERATE_COMMAND, ChunkFixture.textsChecksum(corpus), ChunkFixture.sha256(bytes), ids);

        Path out = Path.of("target/chunk-fixture");
        Files.createDirectories(out);
        Files.write(out.resolve("vectors.f32"), bytes);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("manifest.json").toFile(), manifest);
        System.out.println("fixture written to " + out.toAbsolutePath() + " — review, then copy both files into "
            + "lumnik-testkit/src/main/resources/" + ChunkFixture.DIR);
    }

    private static String digestOf(HttpClient http, String ollama) throws Exception {
        for (JsonNode model : JSON.readTree(get(http, ollama + "/api/tags")).path("models")) {
            if (model.path("name").asText().startsWith(MODEL + ":")) {
                return model.path("digest").asText();
            }
        }
        throw new IllegalStateException("model " + MODEL + " is not installed in Ollama — run: ollama pull " + MODEL);
    }

    private static List<float[]> embed(HttpClient http, String ollama, List<String> inputs) throws Exception {
        String body = JSON.writeValueAsString(Map.of("model", MODEL, "input", inputs));
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollama + "/api/embed"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama /api/embed answered " + response.statusCode() + ": " + response.body());
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode embedding : JSON.readTree(response.body()).path("embeddings")) {
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " answered " + response.statusCode());
        }
        return response.body();
    }
}

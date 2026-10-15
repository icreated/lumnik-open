package io.lumnik.testkit.search.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One candidate's run: per-class and overall relevance, a local p95, stability, and every isolation failure. */
public record ChunkBenchResult(String candidate, int warmupPasses, int measuredPasses, String corpusFingerprint,
                               FixtureIdentity fixture, Map<String, ClassMetrics> perClass, ClassMetrics overall, List<QueryMetrics> perQuery,
                               double p95LatencyMillis, boolean stableAcrossPasses, List<String> isolationFailures) {

    public record ClassMetrics(int queries, double ndcgAt10, double recallAt10) {
    }

    /**
     * Which frozen vectors and which model a result was measured on. The corpus fingerprint covers the TEXTS only; a
     * result is tied to the vectors it was measured with by this identity, and a baseline or a report built from
     * results of another identity is refused.
     */
    public record FixtureIdentity(int schemaVersion, String modelDigest, String vectorsSha256) {
        public static FixtureIdentity of(ChunkFixture.Manifest manifest) {
            return new FixtureIdentity(manifest.schemaVersion(), manifest.modelDigest(), manifest.vectorsSha256());
        }
    }

    /** One scored query's figures — the per-query table of the report is built from these. */
    public record QueryMetrics(String id, String queryClass, double ndcgAt10, double recallAt10) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public ClassMetrics of(QueryClass queryClass) {
        return perClass.get(queryClass.label());
    }

    public void write(Path path) {
        try {
            Files.createDirectories(path.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ChunkBenchResult read(Path path) {
        try {
            return JSON.readValue(path.toFile(), ChunkBenchResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<ChunkBenchResult> readIfPresent(Path path) {
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }
}

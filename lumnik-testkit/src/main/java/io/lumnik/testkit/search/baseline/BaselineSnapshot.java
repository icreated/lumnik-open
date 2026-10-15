package io.lumnik.testkit.search.baseline;

public record BaselineSnapshot(int schemaVersion, String corpusFingerprint, ProviderInfo provider,
                               Metrics metrics) {

    public record ProviderInfo(String name, String version) {
    }

    public record Metrics(double ndcgAt10, double recallAt10, double p95LatencyMillis) {
    }
}

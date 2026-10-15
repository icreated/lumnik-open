package io.lumnik.testkit.search.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineIOTest {

    @TempDir
    Path tempDir;

    @Test
    void readReturnsEmptyWhenTheFileDoesNotExist() {
        Optional<BaselineSnapshot> result = BaselineIO.read(tempDir.resolve("missing.json"));

        assertThat(result).isEmpty();
    }

    @Test
    void writeThenReadRoundTripsTheSnapshot() {
        BaselineSnapshot snapshot = new BaselineSnapshot(1, "sha256:abc",
                new BaselineSnapshot.ProviderInfo("reference", "1"),
                new BaselineSnapshot.Metrics(0.9, 0.8, 5.0));
        Path target = tempDir.resolve("search-baselines").resolve("reference.json");

        BaselineIO.write(target, snapshot);
        Optional<BaselineSnapshot> reloaded = BaselineIO.read(target);

        assertThat(reloaded).contains(snapshot);
    }

    @Test
    void fingerprintIsStableForTheSameContentAndDiffersOnAnyChange() {
        String fingerprint = BaselineIO.fingerprint("schemaVersion: 1\nseed: 704\n");
        String same = BaselineIO.fingerprint("schemaVersion: 1\nseed: 704\n");
        String changed = BaselineIO.fingerprint("schemaVersion: 1\nseed: 705\n");

        assertThat(fingerprint).isEqualTo(same).startsWith("sha256:");
        assertThat(fingerprint).isNotEqualTo(changed);
    }
}

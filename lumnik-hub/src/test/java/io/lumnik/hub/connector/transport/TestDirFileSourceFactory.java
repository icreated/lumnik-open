package io.lumnik.hub.connector.transport;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.Map;

/**
 * Test-only transport kind {@code test-dir}: a folder of the test's own, read through the remote (FileSource) pipeline
 * of the one-shot ingest door — batches, limits, hooks, post-commit {@code after_process}. Tests reached that pipeline
 * with a local {@code path} until #2001 took the local transport off the door: there, a local ingest is the uploaded
 * file. Unconfined: where a test puts its files is not the hub's layout.
 */
@ApplicationScoped
public class TestDirFileSourceFactory implements FileSourceFactory {

    public static final String KIND = "test-dir";

    @Override public String kind() { return KIND; }

    @Override public FileSource build(Map<String, Object> cfg) {
        return new LocalFileSource(LocalRoot.at(Path.of("/")), (String) cfg.get("path"));
    }
}

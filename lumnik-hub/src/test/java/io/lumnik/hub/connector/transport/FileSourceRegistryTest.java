package io.lumnik.hub.connector.transport;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class FileSourceRegistryTest {

    @Inject FileSourceRegistry registry;

    @Test
    void unknown_kind_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.build(Map.of("kind", "nonsense")));
    }

    @Test
    void resolves_local_when_kind_is_local() throws Exception {
        var dir = Files.createTempDirectory("registry_local_");
        try {
            FileSource src = registry.build(Map.of("kind", "local", "path", dir.toString() + "/*.csv"));
            assertNotNull(src);
            assertInstanceOf(LocalFileSource.class, src);
        } finally {
            dir.toFile().delete();
        }
    }

    @Test
    void null_config_treats_as_local_but_requires_path() {
        assertThrows(IllegalArgumentException.class, () -> registry.build(null),
            "null config defaults to {kind:local} but local requires `path`");
    }
}

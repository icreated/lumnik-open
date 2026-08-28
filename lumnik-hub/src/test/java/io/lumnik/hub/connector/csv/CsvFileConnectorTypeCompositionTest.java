package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.LocalFileSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CsvFileConnectorTypeCompositionTest {

    @Inject FileSourceRegistry registry;

    @Test
    void legacy_config_without_transport_block_defaults_to_local() throws Exception {
        Path dir = Files.createTempDirectory("compat_");
        try {
            Files.write(dir.resolve("legacy.csv"), "x".getBytes());

            Map<String,Object> legacyConfig = new HashMap<>();
            legacyConfig.put("path", dir.toString() + "/*.csv");

            Map<String,Object> transport = CsvFileConnectorType.resolveTransport(legacyConfig);
            assertEquals("local", transport.get("kind"));
            assertEquals(dir.toString() + "/*.csv", transport.get("path"));

            FileSource src = registry.build(transport);
            assertInstanceOf(LocalFileSource.class, src);
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void modern_config_with_transport_block_uses_it_as_is() {
        Map<String,Object> modern = Map.of(
            "transport", Map.of("kind", "local", "path", "/var/imports/*.csv")
        );
        Map<String,Object> transport = CsvFileConnectorType.resolveTransport(modern);
        assertEquals("local", transport.get("kind"));
        assertEquals("/var/imports/*.csv", transport.get("path"));
    }

    @Test
    void empty_config_throws_clear_error() {
        Map<String,Object> empty = new HashMap<>();
        assertThrows(IllegalArgumentException.class,
            () -> CsvFileConnectorType.resolveTransport(empty));
    }
}

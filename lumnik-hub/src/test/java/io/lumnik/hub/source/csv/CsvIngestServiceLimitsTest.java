package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CsvIngestServiceLimitsTest {

    @Inject CsvIngestService service;

    @Test
    void max_file_size_fails_fast() throws Exception {
        Path bigFile = Files.createTempFile("big_", ".csv");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(bigFile)) {
                out.write("id,data\n");
                for (int i = 0; i < 5000; i++) out.write(i + ",xxxxxxxxxxxxxxxxxxxxx\n");
            }
            long actualSize = Files.size(bigFile);

            CsvIngestForm form = new CsvIngestForm();
            form.name = "big_test_" + System.nanoTime();
            form.scope = "system";
            form.transport = new HashMap<>(Map.of(
                "kind", "local",
                "path", bigFile.toAbsolutePath().toString(),
                "max_file_size", 1024L
            ));

            assertTrue(actualSize > 1024L, "Sanity check: file should be > 1KB");

            var ex = assertThrows(RuntimeException.class,
                () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)));
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            // Walk the cause chain for the error keyword.
            Throwable t = ex;
            while (t != null && !msg.contains("max_file_size") && !msg.contains("exceeds")) {
                t = t.getCause();
                if (t != null) msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            }
            assertTrue(msg.contains("max_file_size") || msg.contains("exceeds"),
                "expected max_file_size or exceeds in error chain, got root: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(bigFile);
        }
    }
}

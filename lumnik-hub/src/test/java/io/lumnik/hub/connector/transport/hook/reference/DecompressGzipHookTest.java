package io.lumnik.hub.connector.transport.hook.reference;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.hook.FileSourceHookRunner;
import io.lumnik.hub.connector.transport.hook.HookSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DecompressGzipHookTest {

    @Inject
    FileSourceHookRunner runner;

    @Test
    void decompresses_gzip_stream() throws Exception {
        var raw = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(raw)) {
            gz.write("id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8));
        }
        var hook = new HookSpec("kotlin", "reference/decompress-gzip.kt");
        var file = new FileDescriptor("/x.csv.gz", raw.size(), Instant.now(), null);

        InputStream out = runner.transform(hook, file, new ByteArrayInputStream(raw.toByteArray()));
        String content = new String(out.readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("id,name\n1,alice\n", content);
    }
}

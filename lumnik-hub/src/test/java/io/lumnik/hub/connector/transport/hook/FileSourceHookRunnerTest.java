package io.lumnik.hub.connector.transport.hook;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class FileSourceHookRunnerTest {

    @Inject
    FileSourceHookRunner runner;

    @Test
    void shouldProcess_returns_true_when_hook_is_null() {
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);
        assertTrue(runner.shouldProcess(null, file, null));
    }

    @Test
    void transform_returns_identity_when_hook_is_null() {
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);
        InputStream in = new ByteArrayInputStream("data".getBytes());
        InputStream out = runner.transform(null, file, in);
        assertSame(in, out, "no-op when hook is null");
    }

    @Test
    void postProcess_is_noop_when_hook_is_null() {
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);
        assertDoesNotThrow(() -> runner.postProcess(null, file, null));
    }

    // ── Security: script_ref must be confined to the classpath prefix (Task 6) ──
    //
    // These write a REAL, attacker-controlled .kts file to disk and point an absolute
    // script_ref at it. If the runner ever reads and evals that file, shouldProcess()
    // returns the script's answer (false) instead of throwing — proving the read/eval
    // happened. A correct refusal must happen BEFORE that, so the assertion is on the
    // seam throwing IllegalArgumentException, not merely on an HTTP status code that a
    // later, unrelated failure could also produce.

    @Test
    void shouldProcess_refuses_absolute_script_ref_before_reading_it(@TempDir Path tempDir)
            throws IOException {
        Path evil = tempDir.resolve("evil.kts");
        Files.writeString(evil, "fun shouldProcess(file: Any?, source: Any?): Boolean = false",
            StandardCharsets.UTF_8);

        var hook = new HookSpec("kotlin", evil.toAbsolutePath().toString());
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> runner.shouldProcess(hook, file, null),
            "an absolute script_ref must be refused before the file is read or eval'd — "
            + "if this doesn't throw, the attacker script ran and returned false");
        assertFalse(ex.getMessage().contains(evil.toAbsolutePath().toString()),
            "the refusal must not echo the server-side path back to the caller");
    }

    @Test
    void shouldProcess_refuses_parent_traversal_script_ref() {
        var hook = new HookSpec("kotlin", "../../../../etc/passwd");
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);
        assertThrows(IllegalArgumentException.class, () -> runner.shouldProcess(hook, file, null));
    }

    /**
     * Enumerates every rejection branch of {@code isSafeScriptRef} individually — blank, an
     * embedded null byte, a leading/embedded backslash, a drive-letter or URL-scheme colon — so
     * each is proven refused, not just inferred from code review.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "",                          // blank
        "   ",                       // blank (whitespace only)
        "evil\u0000.kts",            // embedded null byte
        "\\evil.kts",                // leading backslash
        "dir\\evil.kts",             // embedded backslash (Windows separator / UNC)
        "C:\\evil.kts",              // drive letter (colon + backslash)
        "file:evil.kts",             // embedded colon (URL-ish scheme)
    })
    void shouldProcess_refuses_every_isSafeScriptRef_rejection_vector(String scriptRef) {
        var hook = new HookSpec("kotlin", scriptRef);
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);
        assertThrows(IllegalArgumentException.class, () -> runner.shouldProcess(hook, file, null),
            "script_ref [" + scriptRef + "] must be refused");
    }

    @Test
    void shouldProcess_still_loads_a_legitimate_classpath_hook() {
        // reference/skip-if-too-small.kt — same fixture already exercised by
        // SkipIfTooSmallHookTest — must keep working after the refusal is added.
        var hook = new HookSpec("kotlin", "reference/skip-if-too-small.kt");
        var tiny = new FileDescriptor("/tiny.csv", 10, Instant.now(), null);
        assertFalse(runner.shouldProcess(hook, tiny, null));
    }
}

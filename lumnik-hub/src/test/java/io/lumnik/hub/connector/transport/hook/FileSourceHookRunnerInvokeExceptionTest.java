package io.lumnik.hub.connector.transport.hook;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.hook.KotlinScriptHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A script's own failure — its thrown exception, or a signature the runner cannot call — is wrapped
 * as {@code RuntimeException("Hook X#fn() failed")}, never as {@code IllegalArgumentException}: that
 * is the refusal's type, and {@code CsvIngestResource} maps it to a 4xx.
 */
class FileSourceHookRunnerInvokeExceptionTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"test/throws-iae.kt", "test/wrong-signature.kt"})
    void a_scripts_own_failure_is_wrapped_not_mistaken_for_a_refusal(String scriptRef) {
        FileSourceHookRunner runner = new FileSourceHookRunner();
        runner.scriptHost = new KotlinScriptHost();

        var hook = new HookSpec("kotlin", scriptRef);
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> runner.shouldProcess(hook, file, null));
        assertFalse(ex instanceof IllegalArgumentException,
            "a script's own failure must not surface as the refusal's type: " + ex.getClass());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("#shouldProcess()"),
            "must name the hook and the function: " + ex.getMessage());
    }

    @Test
    void a_failure_names_the_kind_and_line_never_the_value_it_could_not_read() {   // #1435
        FileSourceHookRunner runner = new FileSourceHookRunner();
        runner.scriptHost = new KotlinScriptHost();

        var hook = new HookSpec("kotlin", "test/reads-a-value.kt");
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.shouldProcess(hook, file, null));
        assertEquals("Hook test/reads-a-value.kt#shouldProcess() failed: NumberFormatException at line 3",
            ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("env. 4 kg"), "the cause keeps it, for the hub's log");
    }

    @Test
    void the_line_is_the_scripts_not_the_kotlin_stdlibs() {
        FileSourceHookRunner runner = new FileSourceHookRunner();
        runner.scriptHost = new KotlinScriptHost();

        var hook = new HookSpec("kotlin", "test/stdlib-throws.kt");
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.shouldProcess(hook, file, null));
        assertEquals("Hook test/stdlib-throws.kt#shouldProcess() failed: NoSuchElementException at line 3",
            ex.getMessage());
    }
}

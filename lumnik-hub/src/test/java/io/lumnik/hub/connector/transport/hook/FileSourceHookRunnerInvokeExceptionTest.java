package io.lumnik.hub.connector.transport.hook;

import io.lumnik.hub.connector.transport.FileDescriptor;
import org.junit.jupiter.api.Test;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.SimpleBindings;
import java.io.Reader;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 6 (final whole-branch review): {@code HookScriptRefusedException} exists precisely so
 * callers need not preserve every {@code IllegalArgumentException} — but
 * {@link FileSourceHookRunner#invoke} and {@code loadEngine} both caught the broader
 * {@code IllegalArgumentException}, which also lets a hook script's OWN
 * {@code IllegalArgumentException} escape unwrapped (→ 400 via the same path as a real script_ref
 * refusal) instead of being wrapped into the usual "Hook X#fn() failed" {@code RuntimeException}
 * (→ 500) like every other script failure.
 *
 * <p>The real Kotlin JSR-223 engine wraps a script's thrown exception before it reaches these catch
 * blocks (verified: a {@code .kts} script that throws {@code IllegalArgumentException} does NOT
 * surface as a raw {@code IllegalArgumentException} to {@code invoke()} — {@code invokeFunction}
 * wraps it), so a fixture-script test cannot reach the buggy branch. This test drives a hand-written
 * {@link Invocable} directly to pin the catch-block behaviour deterministically.
 */
class FileSourceHookRunnerInvokeExceptionTest {

    /** Throws a caller-supplied exception from every invokeFunction call — nothing else is used. */
    static final class ThrowingEngine extends AbstractScriptEngine implements Invocable {
        private final RuntimeException toThrow;
        ThrowingEngine(RuntimeException toThrow) { this.toThrow = toThrow; }

        @Override public Object invokeFunction(String name, Object... args) { throw toThrow; }
        @Override public Object invokeMethod(Object thiz, String name, Object... args) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T getInterface(Class<T> clasz) { throw new UnsupportedOperationException(); }
        @Override public <T> T getInterface(Object thiz, Class<T> clasz) { throw new UnsupportedOperationException(); }
        @Override public Object eval(String script, ScriptContext context) { throw new UnsupportedOperationException(); }
        @Override public Object eval(Reader reader, ScriptContext context) { throw new UnsupportedOperationException(); }
        @Override public Bindings createBindings() { return new SimpleBindings(); }
        @Override public ScriptEngineFactory getFactory() { throw new UnsupportedOperationException(); }
    }

    @SuppressWarnings("unchecked")
    private static void seedCache(FileSourceHookRunner runner, String scriptRef, ScriptEngine engine) throws Exception {
        Field f = FileSourceHookRunner.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((Map<String, ScriptEngine>) f.get(runner)).put(scriptRef, engine);
    }

    @Test
    void a_script_thrown_illegal_argument_exception_is_wrapped_not_mistaken_for_a_refusal() throws Exception {
        FileSourceHookRunner runner = new FileSourceHookRunner();
        seedCache(runner, "fake-hook", new ThrowingEngine(
            new IllegalArgumentException("script-level refusal, not a script_ref problem")));

        var hook = new HookSpec("kotlin", "fake-hook");
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> runner.shouldProcess(hook, file, null));
        assertFalse(ex instanceof HookScriptRefusedException,
            "a script's own IllegalArgumentException must not be mistaken for a script_ref refusal: "
                + ex.getClass());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("shouldProcess() failed"),
            "must carry the wrapped-failure context: " + ex.getMessage());
    }

    @Test
    void a_script_thrown_illegal_state_exception_is_still_wrapped_as_before() throws Exception {
        // Control: a non-IAE RuntimeException was already wrapped before this fix — must stay so.
        FileSourceHookRunner runner = new FileSourceHookRunner();
        seedCache(runner, "fake-hook-2", new ThrowingEngine(new IllegalStateException("boom")));

        var hook = new HookSpec("kotlin", "fake-hook-2");
        var file = new FileDescriptor("/x.csv", 100, Instant.now(), null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> runner.shouldProcess(hook, file, null));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("shouldProcess() failed"),
            "must carry the wrapped-failure context: " + ex.getMessage());
    }
}

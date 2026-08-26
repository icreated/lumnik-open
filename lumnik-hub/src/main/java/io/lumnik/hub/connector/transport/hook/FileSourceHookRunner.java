package io.lumnik.hub.connector.transport.hook;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invokes Kotlin (.kts) hook scripts at three extension points in the file-source pipeline
 * (one-shot {@code lm csv ingest} only — connector manifests refuse these hooks at validate).
 *
 * <p>Scripts define top-level functions with well-known names:
 * <ul>
 *   <li>{@code shouldProcess(file, source) → Boolean}</li>
 *   <li>{@code transform(file, raw) → InputStream}</li>
 *   <li>{@code postProcess(file, result)}</li>
 * </ul>
 *
 * <p>If a hook is {@code null} or the script does not define the function, the
 * corresponding method is a no-op (shouldProcess defaults to {@code true},
 * transform returns the original stream).
 *
 * <p>Each script is compiled once and cached by {@code scriptRef}.
 * Each script gets its own {@link ScriptEngine} instance so that top-level
 * function definitions are isolated between scripts.
 */
@ApplicationScoped
public class FileSourceHookRunner {

    private static final Logger LOG = Logger.getLogger(FileSourceHookRunner.class);

    /** Cache: scriptRef → engine already loaded with the compiled script. */
    private final ConcurrentHashMap<String, ScriptEngine> cache = new ConcurrentHashMap<>();

    public boolean shouldProcess(HookSpec hook, FileDescriptor file, FileSource source) {
        if (hook == null) return true;
        Object result = invoke(hook, "shouldProcess", file, source);
        return result instanceof Boolean b ? b : true;
    }

    public InputStream transform(HookSpec hook, FileDescriptor file, InputStream raw) {
        if (hook == null) return raw;
        Object result = invoke(hook, "transform", file, raw);
        if (result instanceof InputStream is) return is;
        throw new IllegalStateException(
            "Hook " + hook.scriptRef() + "#transform() must return InputStream, got: "
            + (result == null ? "null" : result.getClass().getName()));
    }

    public void postProcess(HookSpec hook, FileDescriptor file, Object result) {
        if (hook == null) return;
        invoke(hook, "postProcess", file, result);
    }

    // --- internals ---

    private Object invoke(HookSpec hook, String fnName, Object... args) {
        try {
            ScriptEngine engine = cache.computeIfAbsent(hook.scriptRef(), this::loadEngine);
            return ((Invocable) engine).invokeFunction(fnName, args);
        } catch (NoSuchMethodException e) {
            // Script does not define this function — treat as no-op.
            LOG.debugf("Hook %s does not define %s() — skipping", hook.scriptRef(), fnName);
            return null;
        } catch (HookScriptRefusedException e) {
            // A refused script_ref — surface as-is (not wrapped) so callers can map it to a 4xx.
            // Narrowed from IllegalArgumentException: a hook script's OWN IllegalArgumentException
            // must NOT be mistaken for a script_ref refusal — it belongs in the generic wrap below,
            // same as every other script failure.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                "Hook " + hook.scriptRef() + "#" + fnName + "() failed: " + e.getMessage(), e);
        }
    }

    private ScriptEngine loadEngine(String scriptRef) {
        ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("kts");
        if (engine == null) {
            throw new IllegalStateException(
                "Kotlin script engine not available — verify kotlin-scripting-jsr223 is on the classpath");
        }
        String source;
        try {
            source = loadScript(scriptRef);
        } catch (HookScriptRefusedException e) {
            throw e; // a refusal, not a compile failure — surface as-is
        } catch (Exception e) {
            throw new RuntimeException("Hook compile failed for: " + scriptRef, e);
        }
        try {
            engine.eval(source);
            LOG.infof("Loaded and compiled hook script: %s", scriptRef);
            return engine;
        } catch (Exception e) {
            throw new RuntimeException("Hook compile failed for: " + scriptRef, e);
        }
    }

    /**
     * Resolves {@code scriptRef} strictly under {@code META-INF/hooks/connectors/csv-file/} on the
     * classpath. Hooks are one-shot-CLI-only by design (see class javadoc); the HTTP-reachable CSV
     * ingest path must never be able to read an arbitrary filesystem path, so no absolute-path
     * fallback exists here at all — every ref is a classpath lookup, and anything that could steer
     * that lookup outside the intended prefix is refused up front.
     */
    private String loadScript(String scriptRef) throws Exception {
        if (!isSafeScriptRef(scriptRef)) {
            // Deliberately does not echo scriptRef back: it may be an absolute filesystem path,
            // and this refusal must not disclose server-side layout to the caller.
            throw new HookScriptRefusedException(
                "Invalid hook script_ref: must be a relative path under "
                + "META-INF/hooks/connectors/csv-file/ (no absolute paths, '..' segments, "
                + "backslashes, or URL schemes)");
        }
        var stream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("META-INF/hooks/connectors/csv-file/" + scriptRef);
        if (stream == null) {
            throw new HookScriptRefusedException("Hook script not found on classpath: " + scriptRef);
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Rejects anything that could resolve outside {@code META-INF/hooks/connectors/csv-file/}:
     * absolute paths, parent-directory traversal, Windows-style separators / UNC paths, embedded
     * URL/drive-letter schemes (a {@code ':'} anywhere), and null bytes.
     */
    private static boolean isSafeScriptRef(String scriptRef) {
        if (scriptRef == null || scriptRef.isBlank()) return false;
        if (scriptRef.indexOf('\0') >= 0) return false;
        if (scriptRef.startsWith("/") || scriptRef.startsWith("\\")) return false;
        if (scriptRef.contains("..")) return false;
        if (scriptRef.contains("\\")) return false;
        if (scriptRef.contains(":")) return false;
        return true;
    }
}

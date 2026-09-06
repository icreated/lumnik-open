package io.lumnik.hub.connector.transport.hook;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.hook.KotlinScriptHost;
import io.lumnik.hub.hook.kts.KtsScriptRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;

/**
 * Invokes Kotlin hook scripts (the shipped ones are {@code reference/*.kt}; any Kotlin script
 * source on the classpath works) at three extension points in the file-source pipeline
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
 * <p>Each script is compiled once by {@link KotlinScriptHost} (its cache, hot-reload included);
 * each call instantiates it and invokes the function by name — per file, that cost is nothing.
 */
@ApplicationScoped
public class FileSourceHookRunner {

    private static final Logger LOG = Logger.getLogger(FileSourceHookRunner.class);

    private static final String PREFIX = "META-INF/hooks/connectors/csv-file/";

    @Inject KotlinScriptHost scriptHost;

    public boolean shouldProcess(HookSpec hook, FileDescriptor file, FileSource source) {
        if (hook == null) return true;
        Object result = invoke(hook, "shouldProcess", true, file, source);
        return result instanceof Boolean b ? b : true;
    }

    public InputStream transform(HookSpec hook, FileDescriptor file, InputStream raw) {
        if (hook == null) return raw;
        Object result = invoke(hook, "transform", raw, file, raw);
        if (result instanceof InputStream is) return is;
        throw new IllegalStateException(
            "Hook " + hook.scriptRef() + "#transform() must return InputStream, got: "
            + (result == null ? "null" : result.getClass().getName()));
    }

    public void postProcess(HookSpec hook, FileDescriptor file, Object result) {
        if (hook == null) return;
        invoke(hook, "postProcess", null, file, result);
    }

    // --- internals ---

    /** Calls the script's {@code fnName}; {@code ifUndefined} when the script does not define it. */
    private Object invoke(HookSpec hook, String fnName, Object ifUndefined, Object... args) {
        KtsScriptRunner.Script script = load(hook.scriptRef());   // a refusal passes through as-is
        try {
            KtsScriptRunner.ScriptFunction fn = script.function(fnName, args.length);
            if (fn == null) {
                LOG.debugf("Hook %s does not define %s() — skipping", hook.scriptRef(), fnName);
                return ifUndefined;
            }
            return fn.call(args);
        } catch (Exception e) {
            // The script's own failure or shape — wrapped, never the refusal's type.
            throw new RuntimeException(
                "Hook " + hook.scriptRef() + "#" + fnName + "() failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves {@code scriptRef} strictly under {@code META-INF/hooks/connectors/csv-file/} on the
     * classpath, then instantiates it. Hooks are one-shot-CLI-only by design (see class javadoc);
     * the HTTP-reachable CSV ingest path must never be able to read an arbitrary filesystem path,
     * so no absolute-path fallback exists here at all — every ref is a classpath lookup, and
     * anything that could steer that lookup outside the intended prefix is refused up front.
     * A refused script_ref surfaces as-is (not wrapped) so callers can map it to a 4xx.
     */
    private KtsScriptRunner.Script load(String scriptRef) {
        if (!isSafeScriptRef(scriptRef)) {
            // Deliberately does not echo scriptRef back: it may be an absolute filesystem path,
            // and this refusal must not disclose server-side layout to the caller.
            throw new HookScriptRefusedException(
                "Invalid hook script_ref: must be a relative path under " + PREFIX
                + " (no absolute paths, '..' segments, backslashes, or URL schemes)");
        }
        String path = PREFIX + scriptRef;
        if (Thread.currentThread().getContextClassLoader().getResource(path) == null) {
            throw new HookScriptRefusedException("Hook script not found on classpath: " + scriptRef);
        }
        try {
            return scriptHost.instantiate(path);
        } catch (Exception e) {   // compile error, or a script body that throws at load
            throw new RuntimeException("Hook load failed for: " + scriptRef, e);
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

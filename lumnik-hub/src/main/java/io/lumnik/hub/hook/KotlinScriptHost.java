package io.lumnik.hub.hook;

import jakarta.enterprise.context.ApplicationScoped;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads, compiles and evaluates Kotlin (.kts) hook scripts via JSR-223.
 *
 * Scripts receive three bindings:
 *   - "event"  : LifecycleEvent (may be null for action hooks)
 *   - "entity" : the domain object being processed
 *   - "ctx"    : HookContext
 *
 * Compiled scripts are cached by path unless hot-reload is enabled.
 */
@ApplicationScoped
public class KotlinScriptHost {

    private static final Logger LOG = Logger.getLogger(KotlinScriptHost.class);

    @ConfigProperty(name = "lumnik.hooks.kotlin.hot-reload", defaultValue = "false")
    boolean hotReload;

    private final ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("kts");

    // Bounded: the __src__ keys embed whole script sources, and a tenant editing an
    // override mints a fresh key each time — unbounded, this map is a slow leak of
    // heavyweight CompiledScripts.
    private final Cache<String, CompiledScript> cache =
        Caffeine.newBuilder().maximumSize(256).build();

    public void execute(String scriptPath, LifecycleEvent event, Object entity, HookContext ctx)
            throws Exception {
        if (engine == null) {
            throw new IllegalStateException(
                "Kotlin script engine not available; verify kotlin-scripting-jsr223 is on the classpath");
        }
        CompiledScript compiled = hotReload
            ? compile(scriptPath)
            : cache.get(scriptPath, this::compileUnchecked);

        var bindings = engine.createBindings();
        bindings.put("event", event);
        bindings.put("entity", entity);
        bindings.put("ctx", ctx);
        compiled.eval(bindings);
    }

    public void executeSource(String source, LifecycleEvent event, Object entity, HookContext ctx)
            throws Exception {
        if (engine == null) {
            throw new IllegalStateException(
                "Kotlin script engine not available; verify kotlin-scripting-jsr223 is on the classpath");
        }
        String cacheKey = "__src__:" + source;
        CompiledScript compiled = hotReload
            ? ((Compilable) engine).compile(source)
            : cache.get(cacheKey, k -> {
                try {
                    return ((Compilable) engine).compile(source);
                } catch (Exception e) {
                    throw new IllegalStateException("Kotlin source compilation failed", e);
                }
            });
        var bindings = engine.createBindings();
        bindings.put("event", event);
        bindings.put("entity", entity);
        bindings.put("ctx", ctx);
        compiled.eval(bindings);
    }

    /**
     * Generic overload for mapping-engine cell hooks: bindings are passed as-is to the script.
     * The script receives all keys in {@code extraBindings} as top-level bindings.
     */
    public void executeSource(String source, Map<String, Object> extraBindings) throws Exception {
        if (engine == null) {
            throw new IllegalStateException(
                "Kotlin script engine not available; verify kotlin-scripting-jsr223 is on the classpath");
        }
        String cacheKey = "__src__:" + source;
        CompiledScript compiled = hotReload
            ? ((Compilable) engine).compile(source)
            : cache.get(cacheKey, k -> {
                try {
                    return ((Compilable) engine).compile(source);
                } catch (Exception e) {
                    throw new IllegalStateException("Kotlin source compilation failed", e);
                }
            });
        var bindings = engine.createBindings();
        bindings.putAll(extraBindings);
        compiled.eval(bindings);
    }

    private CompiledScript compileUnchecked(String path) {
        try {
            return compile(path);
        } catch (Exception e) {
            throw new IllegalStateException("Kotlin script compilation failed for: " + path, e);
        }
    }

    private CompiledScript compile(String scriptPath) throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource(scriptPath);
        if (url == null) {
            throw new IllegalStateException("Kotlin script not found on classpath: " + scriptPath);
        }
        try (InputStream in = url.openStream()) {
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            LOG.debugf("Compiling Kotlin script: %s", scriptPath);
            return ((Compilable) engine).compile(source);
        }
    }
}

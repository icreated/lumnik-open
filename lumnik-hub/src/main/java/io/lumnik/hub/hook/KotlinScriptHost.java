package io.lumnik.hub.hook;

import jakarta.enterprise.context.ApplicationScoped;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lumnik.hub.hook.kts.KtsScriptRunner;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.script.SimpleBindings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads, compiles and evaluates Kotlin (.kts) hook scripts.
 *
 * A script sees one top-level value, {@code bindings} (a {@code javax.script.Bindings}), and reads
 * its inputs from it — {@code bindings["event"]}, never a bare {@code event}. Lifecycle hooks get:
 *   - "event"  : LifecycleEvent (may be null for action hooks)
 *   - "entity" : the domain object being processed
 *   - "ctx"    : HookContext
 *
 * Compiled scripts are cached by classpath path, or by source text; with hot-reload on, the
 * resource is re-read per call and recompiled only when its text changed. Each evaluation is
 * one constructor call — see {@link KtsScriptRunner}.
 */
@ApplicationScoped
public class KotlinScriptHost {

    private static final Logger LOG = Logger.getLogger(KotlinScriptHost.class);

    @ConfigProperty(name = "lumnik.hooks.kotlin.hot-reload", defaultValue = "false")
    boolean hotReload;

    private final KtsScriptRunner runner = new KtsScriptRunner();

    // Bounded: the __src__ keys embed whole script sources, and a tenant editing an
    // override mints a fresh key each time — unbounded, this map is a slow leak of
    // heavyweight compiled scripts.
    private final Cache<String, KtsScriptRunner.Compiled> cache =
        Caffeine.newBuilder().maximumSize(256).build();

    public void execute(String scriptPath, LifecycleEvent event, Object entity, HookContext ctx)
            throws Exception {
        compiledClasspath(scriptPath).evaluate(lifecycleBindings(event, entity, ctx));
    }

    public void executeSource(String source, LifecycleEvent event, Object entity, HookContext ctx)
            throws Exception {
        compiledSource(source).evaluate(lifecycleBindings(event, entity, ctx));
    }

    /**
     * Mapping-engine cell hooks, script on the classpath: every key of {@code bindings} is readable
     * in the script as {@code bindings["<key>"]} (the cell hook passes "cells" and "context").
     * The resource is read and compiled once per path, whatever the number of rows. The map is
     * wrapped, not copied (this runs per row): pass a mutable one if the script assigns into
     * {@code bindings} itself — every shipped script writes into {@code cells} instead.
     */
    public void execute(String scriptPath, Map<String, Object> bindings) throws Exception {
        compiledClasspath(scriptPath).evaluate(new SimpleBindings(bindings));
    }

    /**
     * A classpath script that DEFINES functions instead of acting on bindings: compiled once per
     * path, run with empty bindings, the running script returned — the functions are its members.
     */
    public KtsScriptRunner.Script instantiate(String scriptPath) {
        return compiledClasspath(scriptPath).instantiate(new SimpleBindings());
    }

    /** Same contract as {@link #execute(String, Map)}, for an inline source. */
    public void executeSource(String source, Map<String, Object> bindings) throws Exception {
        compiledSource(source).evaluate(new SimpleBindings(bindings));
    }

    private KtsScriptRunner.Compiled compiledClasspath(String scriptPath) {
        return compiled(scriptPath, scriptPath, () -> readClasspath(scriptPath));
    }

    private KtsScriptRunner.Compiled compiledSource(String source) {
        // Diagnostics name an inline source by its hash, so two overrides' errors stay apart.
        return compiled("__src__:" + source, "inline-" + Integer.toHexString(source.hashCode()) + ".kts", () -> source);
    }

    private KtsScriptRunner.Compiled compiled(String key, String name, Supplier<String> source) {
        if (hotReload) {
            // Re-read the source every call, but recompile only when its TEXT changed: keyed on
            // the path alone, an edited file would be missed; uncached, a cell hook would run
            // the compiler once per ROW — the very cost this host exists to avoid.
            String text = source.get();
            return cache.get("__src__:" + text, k -> compileLogged(text, name));
        }
        return cache.get(key, k -> compileLogged(source.get(), name));
    }

    private KtsScriptRunner.Compiled compileLogged(String source, String name) {
        LOG.debugf("Compiling Kotlin script: %s", name);
        return runner.compile(source, name);
    }

    private static SimpleBindings lifecycleBindings(LifecycleEvent event, Object entity, HookContext ctx) {
        var bindings = new SimpleBindings();
        bindings.put("event", event);
        bindings.put("entity", entity);
        bindings.put("ctx", ctx);
        return bindings;
    }

    private static String readClasspath(String scriptPath) {
        URL url = Thread.currentThread().getContextClassLoader().getResource(scriptPath);
        if (url == null) {
            throw new IllegalStateException("Kotlin script not found on classpath: " + scriptPath);
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Kotlin script read failed for: " + scriptPath, e);
        }
    }
}

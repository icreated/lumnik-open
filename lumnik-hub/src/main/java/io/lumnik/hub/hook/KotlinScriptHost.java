package io.lumnik.hub.hook;

import jakarta.enterprise.context.ApplicationScoped;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.hub.hook.kts.KtsScriptRunner;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.script.SimpleBindings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 *
 * <p>A cell hook names its script {@code classpath:<resource>} (built into the hub) or
 * {@code hooks:<name>.kts} (#1402): a file of the directory the operator sets with
 * {@code LUMNIK_HOOKS_DIR}. Unset, that scheme is refused — the manifest never carries code nor a path.
 */
@ApplicationScoped
public class KotlinScriptHost {

    private static final Logger LOG = Logger.getLogger(KotlinScriptHost.class);

    private static final String CLASSPATH = "classpath:";
    private static final String HOOKS = "hooks:";
    /**
     * The only names {@code hooks:} takes: a bare file name, so no manifest can name a path. What a file
     * of the directory is — a symlink included, as every key of a Kubernetes ConfigMap mount is — is the
     * operator's, who can already deploy the hub.
     */
    private static final Pattern HOOKS_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*\\.kts");
    /**
     * The only resources {@code classpath:} takes: a relative .kts path without {@code ..}. A manifest
     * is checked at `lm validate`, and the compiler's error quotes what it read — any other resource
     * would hand its content to whoever can validate one.
     */
    private static final Pattern CLASSPATH_SCRIPT = Pattern.compile("(?!.*\\.\\.)[A-Za-z0-9_][A-Za-z0-9_./-]*\\.kts");

    @ConfigProperty(name = "lumnik.hooks.kotlin.hot-reload", defaultValue = "false")
    boolean hotReload;

    @ConfigProperty(name = "lumnik.hooks.dir")
    Optional<String> hooksDir = Optional.empty();

    /** One script of the hooks directory, as {@code lm hook scripts} shows it. */
    public record HookScript(String name, long size, String sha256) {}

    /**
     * A cell hook's script, compiled, with the version it was compiled from (#1430): the reference as the manifest
     * writes it — {@code hooks:} and {@code classpath:} can both name a {@code shipping-fee.kts} — and the SHA-256 of
     * the text.
     */
    public record Loaded(KtsScriptRunner.Compiled compiled, ScriptVersion version) {}

    // hooks: scripts by reference, compiled from the file as check() last read it — at apply and before
    // each chunk, so an edited script is taken up from the next chunk on. A row reads neither the disk
    // nor the script's text: one lookup by the reference it was given.
    private final Map<String, Loaded> hooksCompiled = new ConcurrentHashMap<>();

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
     * A classpath script that DEFINES functions instead of acting on bindings: compiled once per
     * path, run with empty bindings, the running script returned — the functions are its members.
     */
    public KtsScriptRunner.Script instantiate(String scriptPath) {
        return compiledClasspath(scriptPath).instantiate(new SimpleBindings());
    }

    /**
     * Resolves and compiles a cell hook's script reference — at apply and before each chunk, never per
     * row. A reference this hub cannot resolve is an {@link IllegalArgumentException}; a script that
     * does not compile, an {@link IllegalStateException} carrying the compiler's line and column. A
     * hooks: script read again here is the one every connector naming it runs from then on.
     */
    public void check(String reference) {
        if (reference.startsWith(HOOKS)) {
            hooksCompiled.put(reference, loadedHooksFile(reference));
        } else {
            compiled(reference);   // compiles, without reading the text to fingerprint it: the pin does
        }
    }

    /**
     * A cell hook's script, compiled — for a hooks: reference, as the last {@link #check} read it. The
     * host is shared by every run: a chunk pins what this returns, so another run's check() cannot
     * change the script between two of its rows.
     */
    public KtsScriptRunner.Compiled compiled(String reference) {
        return reference.startsWith(CLASSPATH) ? compiledClasspath(classpathResource(reference)) : loaded(reference).compiled();
    }

    /** {@link #compiled}, with the script's name and the SHA-256 of its text — what a run records of it (#1430). */
    public Loaded loaded(String reference) {
        if (reference.startsWith(CLASSPATH)) {
            String resource = classpathResource(reference);
            // One read: the fingerprint is of the text compiled, even when hot reload re-reads the resource.
            String text = readClasspath(resource);
            return new Loaded(compiled(resource, resource, () -> text),
                new ScriptVersion(CLASSPATH + resource, AuditRecorder.sha256(text)));
        }
        if (reference.startsWith(HOOKS)) {
            return hooksCompiled.computeIfAbsent(reference, this::loadedHooksFile);
        }
        throw new IllegalArgumentException(
            "a script is named classpath:<resource> or hooks:<name>.kts, got: " + reference);
    }

    /** The scripts a manifest can name as {@code hooks:}, by name; empty when this hub has no hooks directory. */
    public Optional<List<HookScript>> hooksScripts() {
        if (hooksDir.isEmpty()) return Optional.empty();
        try (Stream<Path> files = Files.list(Path.of(hooksDir.get()))) {
            return Optional.of(files
                .filter(f -> HOOKS_NAME.matcher(f.getFileName().toString()).matches() && Files.isRegularFile(f))
                .sorted()
                .map(f -> {
                    String name = f.getFileName().toString();
                    String text = readHooksFile(name);
                    return new HookScript(name, text.getBytes(StandardCharsets.UTF_8).length, AuditRecorder.sha256(text));
                })
                .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("the hooks directory (LUMNIK_HOOKS_DIR) cannot be listed", e);
        }
    }

    private static String classpathResource(String reference) {
        String resource = reference.substring(CLASSPATH.length()).trim();
        if (resource.isEmpty()) {
            throw new IllegalArgumentException("'classpath:' needs a resource path, got: " + reference);
        }
        if (!CLASSPATH_SCRIPT.matcher(resource).matches()) {
            throw new IllegalArgumentException("classpath: names a .kts script built into the hub — "
                + "a relative path ending in .kts, without '..' — got: " + reference);
        }
        return resource;
    }

    /** Reads and compiles a hooks: script — recompiled only when its text changed. */
    private Loaded loadedHooksFile(String reference) {
        String name = hooksName(reference);
        String text = readHooksFile(name);
        return new Loaded(compiled("__src__:" + text, name, () -> text),
            new ScriptVersion(HOOKS + name, AuditRecorder.sha256(text)));
    }

    private String hooksName(String reference) {
        String name = reference.substring(HOOKS.length());
        if (!HOOKS_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("hooks: takes a file name of the hooks directory — lower-case "
                + "letters, digits and hyphens, ending in .kts, no path — got: " + reference);
        }
        if (hooksDir.isEmpty()) {
            throw new IllegalArgumentException("this hub has no hooks directory, so " + reference
                + " cannot be found: its operator sets LUMNIK_HOOKS_DIR to offer hooks: scripts");
        }
        return name;
    }

    private String readHooksFile(String name) {
        Path file = Path.of(hooksDir.orElseThrow()).resolve(name);
        if (!Files.isRegularFile(file)) {
            // The directory's path is the operator's, never said to the manifest's author.
            throw new IllegalArgumentException("hooks:" + name + " is not in the hooks directory "
                + "(`lm hook scripts` lists what it holds)");
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("hooks:" + name + " cannot be read", e);
        }
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

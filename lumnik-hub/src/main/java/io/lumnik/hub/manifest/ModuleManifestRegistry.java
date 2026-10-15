package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

@ApplicationScoped
public class ModuleManifestRegistry {

    private static final Logger LOG = Logger.getLogger(ModuleManifestRegistry.class);
    private static final String MANIFEST_PATH = "META-INF/module-manifest.yaml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private List<ModuleManifest> manifests = List.of();
    private List<ModuleStatus> statuses = List.of();
    private List<ModuleManifest> active = List.of();
    private List<String> offeredTabs = List.of();

    void onStart(@Observes @Priority(1) StartupEvent ev) {
        Function<String, Optional<String>> config = name -> ConfigProvider.getConfig().getOptionalValue(name, String.class);
        validateSwitches(config);
        use(loadManifests(), allowedBy(config));
        LOG.infof("Platform loaded %d module(s): %s",
            manifests.size(),
            manifests.stream().map(ModuleManifest::id).toList());
    }

    /**
     * Installs a manifest set and computes the verdict once. The seam {@code onStart} uses with the
     * classpath scan, and unit tests with a fabricated set.
     */
    void use(List<ModuleManifest> loaded, Predicate<ModuleManifest> allowed) {
        manifests = List.copyOf(loaded);
        statuses = ModuleStatuses.compute(manifests, allowed);
        active = statuses.stream().filter(ModuleStatus::active).map(ModuleStatus::manifest).toList();
        offeredTabs = computeOfferedTabs(); // once: the offer is immutable after this call, and so is its warning
    }

    /**
     * The operator's verdict: a module that names a switch ({@code enabled_by}) is allowed unless
     * that property is {@code false}; an absent property means allowed. Only the exact strings
     * {@code true} and {@code false} are accepted, because the {@code @LookupIfProperty} seams
     * compare the exact string {@code "true"}: a value this method read as on and they read as off
     * (or the reverse) would half-disable the family, so the hub refuses to start instead.
     */
    static Predicate<ModuleManifest> allowedBy(Function<String, Optional<String>> config) {
        return m -> m.enabledBy() == null || readSwitch(m.enabledBy(), config);
    }

    /** The operator switches the hub knows by name, checked at startup in EVERY build. */
    private static final List<String> SWITCHES = List.of("lumnik.ai.enabled");

    /**
     * Fails fast on a malformed switch even when no installed module names it (an Open build carries
     * none of the AI family, and a typo there must still stop the hub rather than boot silently).
     */
    static void validateSwitches(Function<String, Optional<String>> config) {
        SWITCHES.forEach(name -> readSwitch(name, config));
    }

    private static boolean readSwitch(String name, Function<String, Optional<String>> config) {
        String value = config.apply(name).orElse(null);
        if (value == null || value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalStateException("Invalid value '" + value + "' for " + name
            + ": expected exactly 'true' or 'false'");
    }

    private List<ModuleManifest> loadManifests() {
        List<ModuleManifest> result = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread()
                .getContextClassLoader()
                .getResources(MANIFEST_PATH);

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try {
                    ModuleManifest manifest = yaml.readValue(url, ModuleManifest.class);
                    validate(manifest, url);
                    result.add(manifest);
                    LOG.debugf("Loaded manifest: %s v%s (schema: %s)",
                        manifest.id(), manifest.version(), manifest.schema());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse manifest at " + url, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan module manifests", e);
        }
        return List.copyOf(result);
    }

    private void validate(ModuleManifest m, URL source) {
        if (m.id() == null || m.id().isBlank())
            throw new IllegalStateException("Manifest at " + source + " is missing 'id'");
        if (m.manifestVersion() < 1)
            throw new IllegalStateException("Manifest '" + m.id() + "' has invalid manifest_version");
    }

    /** Every INSTALLED module — what migrations, roles, screens and enums read. Never filtered (D2). */
    public List<ModuleManifest> all() {
        return manifests;
    }

    /** Every installed module with its verdict, in {@link #all()} order. */
    public List<ModuleStatus> statuses() {
        return statuses;
    }

    /** The modules that are carried, allowed and whose dependencies are active. */
    public List<ModuleManifest> active() {
        return active;
    }

    /** What this hub offers the métier PWA: the sorted union of the tabs of the active modules. */
    public List<String> offeredTabs() {
        return offeredTabs;
    }

    private List<String> computeOfferedTabs() {
        TreeSet<String> out = new TreeSet<>();
        for (ModuleManifest m : active()) {
            for (String t : m.tabsOrEmpty()) {
                if (t != null && ModuleManifest.TAB_ID.matcher(t).matches()) out.add(t);
                else LOG.warnf("Module '%s' declares a malformed tab id '%s' — skipped", m.id(), t);
            }
        }
        return List.copyOf(out);
    }

    public ModuleManifest findById(String id) {
        return manifests.stream()
            .filter(m -> m.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No module manifest for id: " + id));
    }
}

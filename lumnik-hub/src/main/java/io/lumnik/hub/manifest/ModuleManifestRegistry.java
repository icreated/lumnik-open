package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@ApplicationScoped
public class ModuleManifestRegistry {

    private static final Logger LOG = Logger.getLogger(ModuleManifestRegistry.class);
    private static final String MANIFEST_PATH = "META-INF/module-manifest.yaml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private List<ModuleManifest> manifests = List.of();

    void onStart(@Observes @Priority(1) StartupEvent ev) {
        manifests = loadManifests();
        LOG.infof("Platform loaded %d module(s): %s",
            manifests.size(),
            manifests.stream().map(ModuleManifest::id).toList());
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

    public List<ModuleManifest> all() {
        return manifests;
    }

    public ModuleManifest findById(String id) {
        return manifests.stream()
            .filter(m -> m.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No module manifest for id: " + id));
    }
}

package io.lumnik.hub.screen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.manifest.HubYaml;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class EnumRegistry {

    private static final Logger LOG = Logger.getLogger(EnumRegistry.class);

    @Inject ModuleManifestRegistry manifestRegistry;

    private final ObjectMapper yaml = HubYaml.mapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Map<String, EnumDefinition> enums = Map.of();

    void onStart(@Observes @Priority(2) StartupEvent ev) {
        Map<String, EnumDefinition> loaded = new HashMap<>();
        for (ModuleManifest manifest : manifestRegistry.all()) {
            List<String> enumIds = manifest.enums();
            if (enumIds == null || enumIds.isEmpty()) continue;

            for (String enumId : enumIds) {
                String path = "META-INF/enums/" + manifest.id() + "/" + enumId + ".yaml";
                URL url = Thread.currentThread().getContextClassLoader().getResource(path);
                if (url == null) {
                    LOG.warnf("Enum '%s/%s' declared in manifest but file not found at: %s",
                        manifest.id(), enumId, path);
                    continue;
                }
                try {
                    EnumDefinition def = yaml.readValue(url, EnumDefinition.class);
                    loaded.put(manifest.id() + "/" + enumId, def);
                    LOG.debugf("Loaded enum: %s/%s (%d values)", manifest.id(), enumId,
                        def.values() != null ? def.values().size() : 0);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse enum at " + url, e);
                }
            }
        }
        enums = Map.copyOf(loaded);
        LOG.infof("Platform loaded %d enum(s): %s", enums.size(), enums.keySet());
    }

    public Optional<EnumDefinition> find(String moduleId, String enumId) {
        return Optional.ofNullable(enums.get(moduleId + "/" + enumId));
    }
}

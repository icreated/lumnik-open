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
public class ScreenRegistry {

    private static final Logger LOG = Logger.getLogger(ScreenRegistry.class);

    @Inject ModuleManifestRegistry manifestRegistry;

    private final ObjectMapper yaml = HubYaml.mapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // key: "{moduleId}/{screenId}"
    private Map<String, ScreenDefinition> screens = Map.of();

    void onStart(@Observes @Priority(2) StartupEvent ev) {
        Map<String, ScreenDefinition> loaded = new HashMap<>();
        for (ModuleManifest manifest : manifestRegistry.all()) {
            List<String> screenIds = manifest.screens();
            if (screenIds == null || screenIds.isEmpty()) continue;

            for (String screenId : screenIds) {
                String path = "META-INF/screens/" + manifest.id() + "/" + screenId + ".yaml";
                URL url = Thread.currentThread().getContextClassLoader().getResource(path);
                if (url == null) {
                    LOG.warnf("Screen '%s/%s' declared in manifest but file not found at: %s",
                        manifest.id(), screenId, path);
                    continue;
                }
                try {
                    ScreenDefinition screen = yaml.readValue(url, ScreenDefinition.class);
                    loaded.put(manifest.id() + "/" + screenId, screen);
                    LOG.debugf("Loaded screen: %s/%s (%s)", manifest.id(), screenId, screen.type());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse screen at " + url, e);
                }
            }
        }
        screens = Map.copyOf(loaded);
        LOG.infof("Platform loaded %d screen(s): %s", screens.size(), screens.keySet());
    }

    public Optional<ScreenDefinition> find(String moduleId, String screenId) {
        return Optional.ofNullable(screens.get(moduleId + "/" + screenId));
    }

    public Map<String, ScreenDefinition> all() {
        return screens;
    }
}

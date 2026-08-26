package io.lumnik.hub.connector.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class FileSourceRegistry {

    @Inject Instance<FileSourceFactory> factories;

    public FileSource build(Map<String, Object> transportCfg) {
        if (transportCfg == null) transportCfg = Map.of("kind", "local");
        String kind = (String) transportCfg.getOrDefault("kind", "local");
        for (FileSourceFactory f : factories) {
            if (f.kind().equals(kind)) return f.build(transportCfg);
        }
        throw new IllegalArgumentException("Unknown transport kind: " + kind);
    }
}

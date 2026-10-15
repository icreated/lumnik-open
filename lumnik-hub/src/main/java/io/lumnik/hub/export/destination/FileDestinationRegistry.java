package io.lumnik.hub.export.destination;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class FileDestinationRegistry {

    @Inject Instance<FileDestinationFactory> factories;

    public FileDestination build(Map<String, Object> cfg) {
        if (cfg == null)
            throw new IllegalArgumentException("destination config must not be null — there is no default destination kind");
        String kind = (String) cfg.get("kind");
        if (kind == null)
            throw new IllegalArgumentException("destination config must declare 'kind'");
        for (FileDestinationFactory f : factories) {
            if (f.kind().equals(kind)) return f.build(cfg);
        }
        throw new IllegalArgumentException("Unknown destination kind: " + kind);
    }
}

package io.lumnik.hub.connector.transport;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class LocalFileSourceFactory implements FileSourceFactory {

    @Override
    public String kind() { return "local"; }

    @Override
    public FileSource build(Map<String, Object> cfg) {
        String path = (String) cfg.get("path");
        if (path == null || path.isBlank())
            throw new IllegalArgumentException("local transport requires `path`");
        return new LocalFileSource(path);
    }
}

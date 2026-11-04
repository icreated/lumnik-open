package io.lumnik.hub.connector.transport;

import io.lumnik.common.text.Text;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class LocalFileSourceFactory implements FileSourceFactory {

    @Override
    public String kind() { return "local"; }

    @Override
    public FileSource build(Map<String, Object> cfg) {
        String path = (String) cfg.get("path");
        if (Text.blank(path))
            throw new IllegalArgumentException("local transport requires `path`");
        return new LocalFileSource(path);
    }
}

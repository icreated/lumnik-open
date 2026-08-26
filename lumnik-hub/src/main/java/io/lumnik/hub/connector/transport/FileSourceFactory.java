package io.lumnik.hub.connector.transport;

import java.util.Map;

public interface FileSourceFactory {
    String kind();
    FileSource build(Map<String, Object> cfg);
}

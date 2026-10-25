package io.lumnik.hub.rag.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.hub.manifest.HubYaml;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OpenApiChunkBuilder {

    private static final Logger LOG = Logger.getLogger(OpenApiChunkBuilder.class);
    private static final ObjectMapper YAML = HubYaml.mapper();

    public List<ExtractedChunk> buildFromClasspath() {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/openapi.yaml")) {
            if (is == null) {
                LOG.warn("META-INF/openapi.yaml not found on classpath — skipping openapi chunks");
                return List.of();
            }
            return buildFromYaml(new String(is.readAllBytes()));
        } catch (IOException e) {
            LOG.warnf(e, "Could not read openapi.yaml — skipping openapi chunks");
            return List.of();
        }
    }

    public List<ExtractedChunk> buildFromYaml(String yamlContent) {
        List<ExtractedChunk> chunks = new ArrayList<>();
        try {
            JsonNode root = YAML.readTree(yamlContent);
            JsonNode paths = root.get("paths");
            if (paths == null) return List.of();

            paths.fieldNames().forEachRemaining(path -> {
                JsonNode pathItem = paths.get(path);
                for (String method : List.of("get", "post", "put", "patch", "delete")) {
                    JsonNode op = pathItem.get(method);
                    if (op == null) continue;

                    String summary = op.has("summary") ? op.get("summary").asText() : "";
                    String tag = (op.has("tags") && op.get("tags").size() > 0)
                        ? op.get("tags").get(0).asText() : "";

                    chunks.add(new ExtractedChunk("operation", null,
                        method.toUpperCase() + " " + path,
                        String.format("%s %s — tag: %s — %s", method.toUpperCase(), path, tag, summary),
                        Map.of("method", method.toUpperCase(), "path", path, "tag", tag)));
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Could not parse openapi YAML — skipping openapi chunks");
        }
        return chunks;
    }
}

package io.lumnik.hub.rag.chunk;

import io.lumnik.extraction.spi.ExtractedChunk;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ManifestChunkBuilder {

    public List<ExtractedChunk> build(
        String moduleId, String displayName, String schema,
        List<String> permissions, List<String> events
    ) {
        List<ExtractedChunk> chunks = new ArrayList<>();

        String overviewContent = String.format(
            "Module %s (%s) — schéma DB: %s — permissions: %s — events publiés: %s",
            moduleId, displayName, schema,
            String.join(", ", permissions),
            events.isEmpty() ? "aucun" : String.join(", ", events)
        );
        chunks.add(new ExtractedChunk("overview", moduleId,
            moduleId + "/overview", overviewContent,
            Map.of("moduleId", moduleId, "schema", schema)));

        for (String eventName : events) {
            chunks.add(new ExtractedChunk("event", moduleId, eventName,
                String.format("Event %s (module %s) — émis lors d'un changement d'état métier",
                    eventName, moduleId),
                Map.of("moduleId", moduleId, "eventName", eventName)));
        }

        return chunks;
    }
}

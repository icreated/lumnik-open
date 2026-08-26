package io.lumnik.hub.rag.chunk;

import io.lumnik.extraction.spi.ExtractedChunk;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class HookChunkBuilder {

    public List<ExtractedChunk> build(
        String hookId, String moduleId, String lifecycle,
        String trigger, List<String> bindings
    ) {
        String content = String.format(
            "Hook %s (module %s) — déclenché: %s sur %s — bindings: %s",
            hookId, moduleId, lifecycle, trigger,
            String.join(", ", bindings)
        );
        return List.of(new ExtractedChunk("definition", moduleId, hookId, content,
            Map.of("hookId", hookId, "moduleId", moduleId, "lifecycle", lifecycle)));
    }
}

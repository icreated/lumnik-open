package io.lumnik.hub.rag;

import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.extraction.spi.ExtractionSink;
import io.lumnik.hub.hook.HookRegistry;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.hub.rag.chunk.HookChunkBuilder;
import io.lumnik.hub.rag.chunk.ManifestChunkBuilder;
import io.lumnik.hub.rag.chunk.OpenApiChunkBuilder;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlatformIndexer {

    private static final Logger LOG = Logger.getLogger(PlatformIndexer.class);

    @Inject ModuleManifestRegistry manifestRegistry;
    @Inject Instance<io.lumnik.llm.spi.EmbeddingPort> embeddingPorts;
    @Inject HookRegistry hookRegistry;
    @Inject ManifestChunkBuilder manifestChunkBuilder;
    @Inject OpenApiChunkBuilder openApiChunkBuilder;
    @Inject HookChunkBuilder hookChunkBuilder;

    @Inject Instance<ExtractionSink> sinks;

    @ConfigProperty(name = "lumnik.rag.index-on-startup", defaultValue = "true")
    boolean indexOnStartup;

    private final Set<String> initializedSinks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger lastChunkCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastIndexedAt = new AtomicReference<>();

    void onStart(@Observes @Priority(10) StartupEvent ev) {
        if (indexOnStartup) reindex();
    }

    public void reindex() {
        List<ExtractedChunk> chunks = new ArrayList<>();
        chunks.addAll(chunksFromManifests());
        chunks.addAll(openApiChunkBuilder.buildFromClasspath());
        chunks.addAll(chunksFromHooks());

        int sinkCount = 0;
        for (ExtractionSink sink : sinks) {
            if (!ensureInit(sink)) continue;
            try {
                sink.index(0L, "system", List.of("system"), chunks);
                sinkCount++;
            } catch (Exception e) {
                LOG.errorf(e, "Sink '%s' failed during index — continuing with others", sink.name());
            }
        }
        lastChunkCount.set(chunks.size());
        lastIndexedAt.set(Instant.now());
        LOG.infof("Platform index: %d chunks across %d sink(s)", chunks.size(), sinkCount);
    }

    public RagStatusDto status() {
        // Truthful self-description: read the LIVE embedding port, never constants — the old
        // hardcoded "ollama" kept reassuring after a rebinding (audit P2). No port (open
        // edition) is answered honestly with "none".
        String provider = embeddingPorts.isUnsatisfied() ? "none" : embeddingPorts.get().name();
        String model    = embeddingPorts.isUnsatisfied() ? "none" : embeddingPorts.get().model();
        return new RagStatusDto(
            lastIndexedAt.get() != null,
            lastChunkCount.get(),
            lastIndexedAt.get(),
            provider,
            model
        );
    }

    private synchronized boolean ensureInit(ExtractionSink sink) {
        if (initializedSinks.contains(sink.name())) return true;
        try {
            sink.init();
            initializedSinks.add(sink.name());
            return true;
        } catch (Exception e) {
            LOG.errorf(e, "Sink '%s' failed init — will be skipped this run", sink.name());
            return false;
        }
    }

    // Package-private for tests. PlatformIndexer is @ApplicationScoped so its
    // state persists across test methods; tests call this to reset between cases.
    void resetInitState() {
        initializedSinks.clear();
    }

    @PreDestroy
    void onStop() {
        for (ExtractionSink sink : sinks) {
            if (initializedSinks.contains(sink.name())) {
                try { sink.close(); } catch (Exception ignored) {}
            }
        }
    }

    private List<ExtractedChunk> chunksFromManifests() {
        List<ExtractedChunk> result = new ArrayList<>();
        for (ModuleManifest m : manifestRegistry.all()) {
            String displayName = m.displayName() != null
                ? m.displayName().getOrDefault("en", m.id()) : m.id();
            String schema = m.schema() != null ? m.schema() : "";
            List<String> perms = m.permissions() != null
                ? m.permissions().stream().map(ModuleManifest.ManifestPermission::id).toList()
                : List.of();
            List<String> events = m.eventsPublished() != null ? m.eventsPublished() : List.of();
            result.addAll(manifestChunkBuilder.build(m.id(), displayName, schema, perms, events));
        }
        return result;
    }

    private List<ExtractedChunk> chunksFromHooks() {
        List<ExtractedChunk> result = new ArrayList<>();
        for (var h : hookRegistry.allBindings()) {
            String moduleId = h.module() != null ? h.module() : "platform";
            String lifecycle = h.events() != null && !h.events().isEmpty()
                ? h.events().stream().map(Enum::name).collect(Collectors.joining(","))
                : (h.actionPhase() != null ? h.actionPhase().name() : "UNKNOWN");
            String trigger = h.targetClassName() != null ? h.targetClassName()
                : h.action() != null ? h.action() : "";
            result.addAll(hookChunkBuilder.build(h.id(), moduleId, lifecycle, trigger, List.of()));
        }
        return result;
    }

}

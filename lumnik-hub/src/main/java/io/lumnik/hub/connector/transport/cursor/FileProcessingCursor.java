package io.lumnik.hub.connector.transport.cursor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lumnik.hub.connector.transport.FileDescriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cursor serialized as JSON into the engine's {@code ConnectorCursor} (stored in
 * {@code connector.connector_cursor}, keyed by endpoint).
 *
 * <p>JSON shape: {@code {"watermark":"…","lastBatch":[{"path":"…","size":0,"etag":"…"}]}}
 *
 * <p>Legacy {@code {"value":"…"}} format (rest-connector-v1) is treated as an empty cursor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileProcessingCursor(Instant watermark, List<Entry> lastBatch) {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String WATERMARK_KEY = "watermark";
    private static final String LAST_BATCH = "lastBatch";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String path, long size, String etag) {
        /** The file this entry recorded: the batch is the files of the watermark second, so that is its mtime. */
        public FileDescriptor descriptor(Instant watermark) {
            return new FileDescriptor(path, size, watermark, etag);
        }
    }

    /**
     * Returns {@code true} when the given file was already processed in the last batch: the same path, and the same
     * bytes by the one rule, {@link FileDescriptor#sameVersion} — size, mtime, and etag when both carry one. The
     * batch holds the files of the watermark second, so each entry's mtime is the watermark: a rewrite with the same
     * name and size but a newer mtime is a new version (#1381) — an sftp file carries no etag to tell it otherwise.
     * A same-size rewrite within the same mtime second still reads as the same version: the transport's resolution.
     */
    public boolean alreadyProcessed(FileDescriptor file) {
        if (lastBatch == null) return false;
        for (Entry e : lastBatch) {
            if (e.path().equals(file.path())
                && e.descriptor(watermark).sameVersion(file)) return true;
        }
        return false;
    }

    public static String toJson(FileProcessingCursor cursor) {
        try {
            return JSON.writeValueAsString(cursor);
        } catch (Exception e) {
            throw new RuntimeException("serialize cursor", e);
        }
    }

    /**
     * Parses a cursor from JSON. Returns {@link #defaultCursor()} for null, blank,
     * or legacy {@code {"value":"…"}} payloads.
     */
    public static FileProcessingCursor fromJson(String raw) {
        if (raw == null || raw.isBlank()) return defaultCursor();
        try {
            var node = JSON.readTree(raw);
            if (!node.has(WATERMARK_KEY)) return defaultCursor();
            Instant watermark = node.get(WATERMARK_KEY).isNull()
                ? Instant.EPOCH
                : Instant.parse(node.get(WATERMARK_KEY).asText());
            List<Entry> batch = new ArrayList<>();
            if (node.has(LAST_BATCH) && node.get(LAST_BATCH).isArray()) {
                for (var item : node.get(LAST_BATCH)) {
                    String etag = item.has("etag") && !item.get("etag").isNull()
                        ? item.get("etag").asText()
                        : null;
                    batch.add(new Entry(
                        item.path("path").asText(),
                        item.path("size").asLong(),
                        etag
                    ));
                }
            }
            return new FileProcessingCursor(watermark, batch);
        } catch (Exception e) {
            return defaultCursor();
        }
    }

    public static FileProcessingCursor defaultCursor() {
        return new FileProcessingCursor(Instant.EPOCH, List.of());
    }
}

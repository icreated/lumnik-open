package io.lumnik.hub.connector.transport.cursor;

import io.lumnik.hub.connector.transport.FileDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileProcessingCursorTest {

    @Test
    void serialize_and_parse_round_trip() {
        var cursor = new FileProcessingCursor(
            Instant.parse("2026-05-25T14:00:00Z"),
            List.of(
                new FileProcessingCursor.Entry("/a.csv", 100L, "etag1"),
                new FileProcessingCursor.Entry("/b.csv", 200L, null)
            )
        );
        String json = FileProcessingCursor.toJson(cursor);
        FileProcessingCursor parsed = FileProcessingCursor.fromJson(json);

        assertEquals(cursor.watermark(), parsed.watermark());
        assertEquals(2, parsed.lastBatch().size());
        assertEquals("/a.csv", parsed.lastBatch().get(0).path());
        assertEquals(100L, parsed.lastBatch().get(0).size());
        assertEquals("etag1", parsed.lastBatch().get(0).etag());
    }

    @Test
    void empty_or_legacy_cursor_returns_default() {
        var legacy = FileProcessingCursor.fromJson("{\"value\":\"old-cursor\"}");
        assertEquals(Instant.EPOCH, legacy.watermark());
        assertTrue(legacy.lastBatch().isEmpty());

        var nullCursor = FileProcessingCursor.fromJson(null);
        assertEquals(Instant.EPOCH, nullCursor.watermark());

        var blank = FileProcessingCursor.fromJson("");
        assertEquals(Instant.EPOCH, blank.watermark());
    }

    @Test
    void dedupes_files_already_in_lastBatch() {
        var cursor = new FileProcessingCursor(
            Instant.parse("2026-05-25T14:00:00Z"),
            List.of(new FileProcessingCursor.Entry("/already.csv", 100L, "e1"))
        );
        var fresh = new FileDescriptor("/fresh.csv", 200L, Instant.now(), null);
        var dup   = new FileDescriptor("/already.csv", 100L, Instant.now(), "e1");

        assertFalse(cursor.alreadyProcessed(fresh));
        assertTrue(cursor.alreadyProcessed(dup));
    }
}

package io.lumnik.hub.connector.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class LocalFileSourceTest {

    @TempDir
    Path dir;

    @Test
    void list_returns_files_matching_glob_filter_by_mtime() throws Exception {
        Path f1 = Files.write(dir.resolve("a.csv"), "x".getBytes());
        Path f2 = Files.write(dir.resolve("b.csv"), "y".getBytes());
        Files.write(dir.resolve("c.tmp"), "z".getBytes());
        Files.setLastModifiedTime(f1, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(f2, FileTime.from(Instant.parse("2026-03-01T00:00:00Z")));

        var source = new LocalFileSource(dir.toString() + "/*.csv");
        List<FileDescriptor> all = source.listSince(Instant.EPOCH).collect(Collectors.toList());
        assertEquals(2, all.size(), "should exclude .tmp");
        assertEquals("a.csv", all.get(0).name(), "should be sorted by mtime ASC");
        assertEquals("b.csv", all.get(1).name());

        List<FileDescriptor> since = source.listSince(Instant.parse("2026-02-01T00:00:00Z")).collect(Collectors.toList());
        assertEquals(1, since.size());
        assertEquals("b.csv", since.get(0).name());
    }

    @Test
    void open_returns_streaming_inputstream() throws Exception {
        Files.write(dir.resolve("data.csv"), "hello,world".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();
        try (InputStream in = source.open(desc)) {
            assertEquals("hello,world", new String(in.readAllBytes()));
        }
    }

    @Test
    void markProcessed_delete_removes_file() throws Exception {
        Path f = Files.write(dir.resolve("doomed.csv"), "x".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();
        source.markProcessed(desc, AfterProcessAction.DELETE, Map.of());
        assertFalse(Files.exists(f));
    }

    @Test
    void markProcessed_leave_is_noop() throws Exception {
        Path f = Files.write(dir.resolve("kept.csv"), "x".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();
        source.markProcessed(desc, AfterProcessAction.LEAVE, Map.of());
        assertTrue(Files.exists(f));
    }

    @Test
    void openAt_starts_stream_exactly_at_the_requested_byte_offset() throws Exception {
        String content = "0123456789ABCDEFGHIJ"; // 20 bytes, ASCII so byte offset == char offset
        Files.write(dir.resolve("seek.csv"), content.getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        try (InputStream in = source.openAt(desc, 10)) {
            assertEquals("ABCDEFGHIJ", new String(in.readAllBytes()),
                "stream must start at byte 10, not byte 0 skipped-and-discarded");
        }
    }

    @Test
    void openAt_zero_offset_yields_the_whole_file() throws Exception {
        Files.write(dir.resolve("zero.csv"), "hello,world".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        try (InputStream in = source.openAt(desc, 0)) {
            assertEquals("hello,world", new String(in.readAllBytes()));
        }
    }

    @Test
    void openAt_offset_at_eof_yields_an_empty_stream() throws Exception {
        Files.write(dir.resolve("eof.csv"), "hello".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        try (InputStream in = source.openAt(desc, 5)) {
            assertEquals(-1, in.read(), "offset at EOF must be honoured as an empty stream, not an error");
        }
    }

    @Test
    void openAt_negative_offset_throws_rather_than_silently_starting_at_zero() throws Exception {
        Files.write(dir.resolve("neg.csv"), "hello".getBytes());
        var source = new LocalFileSource(dir.toString() + "/*.csv");
        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> source.openAt(desc, -1));
    }
}

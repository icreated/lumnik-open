package io.lumnik.hub.export;

import io.lumnik.hub.export.ExportManifest.Format;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySnapshotStreamerCsvEncodingTest {

    private static Format semicolon(boolean header) {
        return new Format("csv", ';', "UTF-8", header);
    }

    private static Iterator<String[]> rows(String[]... rows) {
        return Arrays.asList(rows).iterator();
    }

    /** Exact byte fixture, computed independently of this implementation. */
    @Test
    void exactBytesAndSha256ForNullAndEmptyString() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        SnapshotStats stats = streamer.writeCsv(List.of("code", "name"),
            rows(new String[]{"1", null}, new String[]{"2", ""}), Format.DEFAULT, out);

        String expected = "\"code\",\"name\"\r\n\"1\",\r\n\"2\",\"\"\r\n";
        assertEquals(expected, out.toString(StandardCharsets.UTF_8));
        assertEquals(2, stats.rowCount());
        assertEquals(29, stats.byteCount());
        assertEquals("36d860710df168ea37959fb566c2b18ed4c1aca73ee37cbed068b6f756969079", stats.sha256Hex());
    }

    @Test
    void internalQuotesDelimitersAndNewlinesAreEscaped() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        streamer.writeCsv(List.of("a", "b"),
            rows(new String[]{"x,y", "He said \"hi\"\nline2"}), Format.DEFAULT, out);

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"x,y\""), "embedded delimiter must be quoted: " + text);
        assertTrue(text.contains("\"He said \"\"hi\"\"\nline2\""), "internal quotes doubled: " + text);
    }

    @Test
    void headerIsAlwaysPresentAndQuoted() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        streamer.writeCsv(List.of("code", "name"), rows(), Format.DEFAULT, out);

        assertEquals("\"code\",\"name\"\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void declaredDelimiterSeparatesHeaderAndRecords() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        streamer.writeCsv(List.of("code", "name"), rows(new String[]{"1", "a;b"}), semicolon(true), out);

        assertEquals("\"code\";\"name\"\r\n\"1\";\"a;b\"\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void headerFalseWritesNoHeaderRecordAndCountsOnlyWhatWasWritten() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        SnapshotStats stats = streamer.writeCsv(List.of("code", "name"),
            rows(new String[]{"1", "Ann"}), semicolon(false), out);

        assertEquals("\"1\";\"Ann\"\r\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(1, stats.rowCount());
        assertEquals(out.size(), stats.byteCount());
    }

    @Test
    void defaultShapeIsTheCommaAndHeaderForm() {
        assertEquals(new Format("csv", ',', "UTF-8", true), Format.DEFAULT);
    }

    /**
     * The FilterOutputStream double-count trap this class must avoid (design doc, "Streaming
     * mechanics"): a sink that records the length of every individual write() call. byteCount
     * must equal the sum of what the sink actually saw, not double it.
     */
    @Test
    void byteCountMatchesTheSinksOwnCumulativeWriteLengthNoDoubleCounting() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        long[] sinkTotal = {0};
        OutputStream tracking = new OutputStream() {
            @Override public void write(int b) { sinkTotal[0] += 1; }
            @Override public void write(byte[] b, int off, int len) { sinkTotal[0] += len; }
        };

        SnapshotStats stats = streamer.writeCsv(List.of("code", "name"),
            rows(new String[]{"1", "Ann"}, new String[]{"2", "Bob"}, new String[]{"3", "Cid"}),
            Format.DEFAULT, tracking);

        assertEquals(sinkTotal[0], stats.byteCount());
    }

    @Test
    void neverClosesTheGivenOutputStream() throws IOException {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        boolean[] closed = {false};
        OutputStream out = new ByteArrayOutputStream() {
            @Override public void close() { closed[0] = true; }
        };

        streamer.writeCsv(List.of("code"), rows(new String[]{"1"}), Format.DEFAULT, out);

        assertTrue(!closed[0], "writeCsv must never close the sink");
    }
}

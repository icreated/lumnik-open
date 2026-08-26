package io.lumnik.hub.connector.transport;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

public interface FileSource extends AutoCloseable {
    /**
     * Files whose {@code lastModified} is at-or-after {@code cursor} (INCLUSIVE boundary, {@code >=}),
     * sorted ascending by {@code lastModified}. Inclusive on purpose: callers track a watermark + a
     * dedup batch (see {@code FileProcessingCursor}); when several files share the watermark second,
     * an exclusive ({@code >}) boundary would advance past the first and permanently skip its siblings.
     * Callers MUST dedup already-processed files (e.g. {@code FileProcessingCursor.alreadyProcessed}).
     */
    Stream<FileDescriptor> listSince(Instant cursor);
    InputStream open(FileDescriptor file);
    void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg);
    @Override default void close() {}
}

package io.lumnik.hub.connector.transport;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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

    /**
     * The descriptor {@link #listSince} would return for {@code path}, or empty when the listing
     * would not return it — the same filters (pattern, prefix, suffix, non-empty) and the same
     * metadata, so {@link FileDescriptor#sameVersion} compares like with like. The {@code since}
     * boundary is left to the caller. One metadata round trip instead of a listing: re-checking
     * the in-progress file every chunk must not walk the whole source (#898).
     */
    Optional<FileDescriptor> stat(String path);

    /**
     * Whether this source can serve the next call without being rebuilt: a cheap check that never connects and never
     * throws. {@code false} only when the transport KNOWS its session is gone. A source that has not connected yet, or
     * whose server answered with an error, is usable. The default is {@code true}: a stateless transport (S3) cannot
     * die between two calls. A source that answers {@code false} is not repaired: the caller closes it and builds
     * another.
     */
    default boolean usable() { return true; }

    InputStream open(FileDescriptor file);
    void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg);
    @Override default void close() {}
}

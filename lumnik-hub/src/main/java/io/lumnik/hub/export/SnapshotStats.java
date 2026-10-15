package io.lumnik.hub.export;

/** Rows/bytes/hash of one completed {@link EntitySnapshotStreamer#stream} call — describes the
 *  exact bytes written to the caller's sink, header and every record separator included. */
public record SnapshotStats(long rowCount, long byteCount, String sha256Hex) {
}

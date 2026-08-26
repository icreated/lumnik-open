package io.lumnik.hub.connector.transport;

import java.time.Instant;

public record FileDescriptor(String path, long size, Instant lastModified, String etag) {
    public String name() {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * The version token for a resumable checkpoint: true when {@code other} describes the
     * same bytes this descriptor pointed to — same {@code size} and {@code lastModified},
     * plus a matching {@code etag} when both sides carry one. A mismatch means the file was
     * mutated since the checkpoint was taken and the checkpoint must not be trusted.
     *
     * <p>{@code path} is deliberately excluded: the caller already holds the descriptor for
     * a specific path and is asking "is this still the same content", not "is this the same
     * file".
     */
    public boolean sameVersion(FileDescriptor other) {
        if (other == null) return false;
        if (size != other.size || !lastModified.equals(other.lastModified)) return false;
        return etag == null || other.etag == null || etag.equals(other.etag);
    }
}

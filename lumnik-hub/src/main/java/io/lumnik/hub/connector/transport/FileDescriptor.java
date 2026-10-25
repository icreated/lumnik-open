package io.lumnik.hub.connector.transport;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record FileDescriptor(String path, long size, Instant lastModified, String etag) {
    private static final DateTimeFormatter ARCHIVE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    public String name() {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * The name this file is archived under (#1382): its own name with its mtime before the extension —
     * {@code livraisons.csv} → {@code livraisons.20261022T163544Z.csv}. A name that comes back every night
     * never collides with an earlier night's, and a retry of the same version targets the same name. A sub-second
     * mtime keeps its fraction ({@code …T163544.123Z}), so two versions {@link #sameVersion} tells apart never share one.
     */
    public String archivedName() {
        String n = name();
        int dot = n.lastIndexOf('.');
        // The fraction only when the source has one (sftp gives whole seconds; s3 milliseconds; local more):
        // three digits for whole milliseconds, nine otherwise — a fixed width, so names sort as times do. Locale.ROOT:
        // ASCII digits whatever the JVM's default locale.
        int nano = lastModified.getNano();
        String fraction = nano == 0 ? ""
            : nano % 1_000_000 == 0 ? String.format(Locale.ROOT, ".%03d", nano / 1_000_000)
            : String.format(Locale.ROOT, ".%09d", nano);
        String stamp = ARCHIVE_STAMP.format(lastModified) + fraction + "Z";
        return dot > 0 ? n.substring(0, dot) + "." + stamp + n.substring(dot) : n + "." + stamp;
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

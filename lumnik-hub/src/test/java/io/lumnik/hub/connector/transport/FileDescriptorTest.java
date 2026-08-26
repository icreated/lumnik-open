package io.lumnik.hub.connector.transport;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The version-token check used to validate a resumable checkpoint at cursor-commit time
 * (design lot-5b, "B1 + B2 — one design"): a checkpoint is only trusted against the same
 * bytes it was taken over. {@link FileDescriptor} already carries {@code size},
 * {@code lastModified} and (for S3) {@code etag} — {@code sameVersion} is nothing more than
 * a documented equality check over those existing fields, no new machinery.
 */
class FileDescriptorTest {

    private static final Instant T1 = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-12T11:00:00Z");

    @Test
    void same_size_and_lastModified_and_no_etag_on_either_side_is_same_version() {
        var a = new FileDescriptor("f.csv", 100, T1, null);
        var b = new FileDescriptor("f.csv", 100, T1, null);
        assertTrue(a.sameVersion(b));
    }

    @Test
    void different_size_is_a_different_version() {
        var a = new FileDescriptor("f.csv", 100, T1, null);
        var b = new FileDescriptor("f.csv", 101, T1, null);
        assertFalse(a.sameVersion(b));
    }

    @Test
    void different_lastModified_is_a_different_version() {
        var a = new FileDescriptor("f.csv", 100, T1, null);
        var b = new FileDescriptor("f.csv", 100, T2, null);
        assertFalse(a.sameVersion(b));
    }

    @Test
    void matching_etag_on_both_sides_confirms_same_version() {
        var a = new FileDescriptor("f.csv", 100, T1, "etag-1");
        var b = new FileDescriptor("f.csv", 100, T1, "etag-1");
        assertTrue(a.sameVersion(b));
    }

    @Test
    void mismatched_etag_on_both_sides_is_a_different_version_even_if_size_and_mtime_agree() {
        var a = new FileDescriptor("f.csv", 100, T1, "etag-1");
        var b = new FileDescriptor("f.csv", 100, T1, "etag-2");
        assertFalse(a.sameVersion(b));
    }

    @Test
    void etag_present_on_only_one_side_is_not_compared() {
        var a = new FileDescriptor("f.csv", 100, T1, "etag-1");
        var b = new FileDescriptor("f.csv", 100, T1, null);
        assertTrue(a.sameVersion(b));
    }

    @Test
    void null_other_is_a_different_version() {
        var a = new FileDescriptor("f.csv", 100, T1, null);
        assertFalse(a.sameVersion(null));
    }
}

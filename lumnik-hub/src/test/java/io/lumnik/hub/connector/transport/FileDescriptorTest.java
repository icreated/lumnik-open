package io.lumnik.hub.connector.transport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

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

    // #1382: the archive name keeps the file's own name and adds its mtime, so a name that comes back
    // every night never collides with an earlier night's archive.
    @Test
    void the_archived_name_carries_the_mtime_before_the_extension() {
        assertEquals("livraisons.20261022T163544Z.csv",
            new FileDescriptor("/out/livraisons.csv", 62, Instant.parse("2026-10-22T16:35:44Z"), null).archivedName());
        // A source with sub-second mtimes (local) keeps them: two versions within one second, two names.
        assertEquals("livraisons.20261022T163544.123Z.csv",
            new FileDescriptor("/out/livraisons.csv", 62, Instant.parse("2026-10-22T16:35:44.123Z"), null).archivedName());
        assertEquals("livraisons.20261022T163544.020Z.csv",
            new FileDescriptor("/out/livraisons.csv", 62, Instant.parse("2026-10-22T16:35:44.02Z"), null).archivedName());
        assertEquals("livraisons.20261022T163544.000000007Z.csv",
            new FileDescriptor("/out/livraisons.csv", 62, Instant.parse("2026-10-22T16:35:44.000000007Z"), null).archivedName());
        assertEquals("export.20260812T100000Z",
            new FileDescriptor("billing/export", 1, T1, null).archivedName());
        assertEquals("a.tar.20260812T100000Z.gz",
            new FileDescriptor("a.tar.gz", 1, T1, null).archivedName());
    }

    // #1384 review: the name is ASCII and sorts as times do, whatever the JVM's default locale.
    @Test
    void the_archived_name_is_ascii_under_any_default_locale() {
        Locale saved = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
            assertEquals("livraisons.20261022T163544.020Z.csv",
                new FileDescriptor("/out/livraisons.csv", 62, Instant.parse("2026-10-22T16:35:44.02Z"), null).archivedName());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, saved);
        }
    }
}

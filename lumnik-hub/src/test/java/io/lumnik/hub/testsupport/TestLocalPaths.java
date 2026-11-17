package io.lumnik.hub.testsupport;

import java.nio.file.Path;

/**
 * A test's own file as a manifest's local {@code path}: relative, as the apply doors require (#2001), and read from
 * {@code /} by {@link AnywhereLocalRoots} — the test application's local root.
 */
public final class TestLocalPaths {

    private static final Path ROOT = Path.of("/");

    private TestLocalPaths() {}

    /** {@code /var/folders/x/a.csv} → {@code var/folders/x/a.csv}: what {@link AnywhereLocalRoots} resolves back. */
    public static String relative(Path file) {
        return ROOT.relativize(file.toAbsolutePath()).toString();
    }
}

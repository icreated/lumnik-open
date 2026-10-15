package io.lumnik.hub.proposal;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The repository root, found rather than assumed: surefire runs with {@code lumnik-hub/} as cwd, an
 * IDE may use the reactor root — the walk testkit's {@code SourceTree} does for the same reason.
 * A method, not a constant: a refusal from a static initializer reaches the report as an
 * unreadable {@code ExceptionInInitializerError}.
 */
final class Repo {

    private static final String MARKER = "lumnik-hub/src/main/resources/" + Catalogue.RESOURCE;

    private Repo() {}

    static Path root() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(MARKER))) return dir;
        }
        throw new IllegalStateException("cannot find " + MARKER + " above " + Path.of("").toAbsolutePath());
    }
}

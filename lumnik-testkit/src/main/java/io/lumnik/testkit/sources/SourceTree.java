package io.lumnik.testkit.sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One module's Java sources, for the inventory tests that police a rule by reading them.
 *
 * <p>Five such tests existed before this class, each carrying its own copy of the same two helpers:
 * the module/reactor cwd fallback and the comment-stripper. The copies had begun to diverge — some
 * strip {@code import} lines in {@code code()}, one did not — which is the cost of copying a
 * guardrail: a reader comparing two guards has to diff their helpers to learn whether the same
 * guarantee holds.
 *
 * <p>It lives in {@code lumnik-testkit} because the rules it serves are not one module's: an
 * identity may not come from a client header in {@code lumnik-identity} any more than in
 * {@code lumnik-hub}, and a sweep run from one module's test cwd cannot reach another's sources
 * without breaking the two-runner support below.
 */
public final class SourceTree {

    private final Path root;

    private SourceTree(Path root) { this.root = root; }

    /**
     * The main sources of one module.
     *
     * <p>Resolved rather than hardcoded: surefire runs with the module as cwd, but an IDE or a
     * custom runner may use the reactor root. A guardrail that only works under one runner is a
     * guardrail someone will quietly stop running.
     *
     * @param module      the Maven module directory, e.g. {@code lumnik-hub}
     * @param packagePath the package as a path, e.g. {@code io/lumnik/hub}
     */
    public static SourceTree of(String module, String packagePath) {
        Path fromModule = Path.of("src/main/java", packagePath);
        if (Files.isDirectory(fromModule)) return new SourceTree(fromModule);
        // Then <module>/src/main/java from here, and from every ancestor. The ancestor walk is
        // what lets a guard in one module police ANOTHER module's sources: surefire's cwd is the
        // module under test, so a sweep living in lumnik-hub cannot see lumnik-identity from
        // either of the two paths above. A rule that spans modules and a guard that cannot leave
        // one reports the shape of its own blind spot rather than the codebase's.
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(module).resolve("src/main/java").resolve(packagePath);
            if (Files.isDirectory(candidate)) return new SourceTree(candidate);
        }
        throw new IllegalStateException(
            "cannot locate " + module + "'s sources from " + Path.of("").toAbsolutePath()
                + " — an inventory cannot police what it cannot find");
    }

    public Path root() { return root; }

    /** Every {@code .java} file under this tree. */
    public List<Path> javaFiles() throws IOException {
        return javaFiles(".java");
    }

    /**
     * Every file whose name ends with {@code suffix}, sorted — the shape an inventory that polices
     * one family of files needs ({@code "Resource.java"}, {@code "ManifestParser.java"}). Sorted so
     * a failure lists the same files in the same order on every machine.
     */
    public List<Path> javaFiles(String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    /** The one file with this name, or empty. */
    public Optional<Path> find(String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().equals(fileName)).findFirst();
        }
    }

    /**
     * The source with comments AND import lines removed. A sentence about a rule never passes for
     * the rule, and a type someone forgot to stop importing is not a type the file uses — mention
     * is not use, for either half of an inventory's question.
     */
    public static String code(String javaSource) {
        return javaSource
            // LINE comments first, and the order is the whole point. A `/*` written inside a
            // line comment — `// lm_integrator on /api/platform/*` in SecretResource — opened a
            // block for the block-stripper, which then ran to the next `*/` and swallowed 140
            // lines: two endpoints and four writes, gone from the text five inventory tests
            // read. A stripper that eats what it was meant to reveal makes every one of them
            // report a clean sweep over a hole. Found by BlindReadInventoryTest, whose scan lost
            // its place inside that hole and said so instead of skipping.
            .replaceAll("(?m)//[^\n]*", " ")
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)^\\s*import\\s+[^;]+;", " ");
    }
}

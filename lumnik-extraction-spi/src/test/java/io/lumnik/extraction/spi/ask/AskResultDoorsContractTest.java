package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AskResult#DOORS} is a contract three languages read independently (#522): {@code lm
 * ask}, the TUI's :ask page, and the PWA's {@code format.mjs} each hardcode the same tokens, and
 * nothing previously crossed the JVM boundary to catch a rename — {@code AbstentionSignpostTest}
 * only pins the tokens against the Java-side signposts. The oracle at {@code
 * contracts/ask-door-tokens.txt} is what both sides now read: a Go and a Node test compare their
 * own vocabulary against the same file, so a change to any one of the copies without updating
 * the oracle breaks that language's own test instead of staying invisible.
 */
class AskResultDoorsContractTest {

    private static final String ORACLE_RELATIVE_PATH = "contracts/ask-door-tokens.txt";

    @Test
    void doorsMatchTheCrossLanguageOracle() throws IOException {
        List<String> oracle = readOracle();
        List<String> doors = new ArrayList<>(AskResult.DOORS);

        Collections.sort(oracle);
        Collections.sort(doors);

        assertThat(doors)
            .as("AskResult.DOORS must match %s exactly, duplicates included", ORACLE_RELATIVE_PATH)
            .isEqualTo(oracle);
    }

    private static List<String> readOracle() throws IOException {
        Path file = repoRoot().resolve(ORACLE_RELATIVE_PATH);
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    // Surefire's cwd is this module's basedir, but an IDE runner may use the reactor root
    // instead — walk up until the oracle is found, same technique as testkit's SourceTree.
    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(ORACLE_RELATIVE_PATH))) return dir;
        }
        throw new IllegalStateException(
            "could not find " + ORACLE_RELATIVE_PATH + " above " + Path.of("").toAbsolutePath());
    }
}

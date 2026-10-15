package io.lumnik.hub.proposal;

import io.lumnik.hub.proposal.DossierReader.Reason;
import io.lumnik.hub.proposal.DossierReader.Result;
import io.lumnik.hub.proposal.DossierReader.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proposal-dossier corpus is the executable contract (spec section 4): the directory is the
 * expected verdict, an invalid fixture's stem is the expected reason. Slices 2 and 3 judge their own
 * readers against these same files; this class only proves the corpus says what the spec says.
 */
class DossierFixturesTest {

    static Stream<Path> fixtures() throws IOException {
        return Files.walk(Repo.root().resolve("lumnik-hub/src/test/resources/proposal/fixtures"))
            .filter(p -> p.toString().endsWith(".md")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void every_fixture_gets_its_expected_verdict_for_the_expected_reason(Path fixture) throws IOException {
        Result result = DossierReader.judge(Files.readString(fixture));
        String dir = fixture.getParent().getFileName().toString();
        assertThat(result.verdict().dir()).as("verdict of %s (reason %s)", fixture, result.reason()).isEqualTo(dir);
        if (result.verdict() == Verdict.INVALID) {
            assertThat(result.reason().code()).as("reason of %s", fixture).isEqualTo(expectedReason(fixture));
        }
    }

    /** The inventory is the guardian: a reason no fixture exercises is a rule nobody tested. */
    @Test
    void every_reason_and_every_verdict_has_a_fixture() throws IOException {
        List<Path> all;
        try (Stream<Path> walk = fixtures()) {   // JUnit closes a @MethodSource stream; this one is ours
            all = walk.toList();
        }
        Set<String> reasons = all.stream().filter(p -> p.getParent().getFileName().toString().equals("invalid"))
            .map(DossierFixturesTest::expectedReason).collect(Collectors.toSet());
        assertThat(reasons).containsExactlyInAnyOrderElementsOf(Arrays.stream(Reason.values()).map(Reason::code).toList());
        Set<String> dirs = all.stream().map(p -> p.getParent().getFileName().toString()).collect(Collectors.toSet());
        assertThat(dirs).containsExactlyInAnyOrder("valid", "valid-recheck", "invalid");
    }

    private static String expectedReason(Path fixture) {
        String stem = fixture.getFileName().toString().replaceFirst("\\.md$", "");
        int cut = stem.indexOf("--");
        return cut < 0 ? stem : stem.substring(0, cut);
    }
}

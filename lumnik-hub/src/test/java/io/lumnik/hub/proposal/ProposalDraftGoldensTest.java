package io.lumnik.hub.proposal;

import io.lumnik.hub.workflow.WorkflowManifest;
import io.lumnik.hub.workflow.WorkflowManifestParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code lm proposal draft} writes (the goldens of {@code lm/internal/proposal/testdata}) judged
 * by the real parser: a draft is never an applicable manifest, and the one complete form is
 * accepted once a graph is added — which pins every key name the writer uses against the parser,
 * the direction a typo (spec.on.tabel) would hide in.
 */
class ProposalDraftGoldensTest {

    private static final String GRAPH = """
          states:
            - { code: OPEN, label: "Open", initial: true }
            - { code: DONE, label: "Done", terminal: true }
          transitions:
            - { from: [OPEN], to: DONE }
        """;

    private final WorkflowManifestParser parser = new WorkflowManifestParser();

    static Path dir() {
        return Repo.root().resolve("lm/internal/proposal/testdata");
    }

    static Stream<Path> goldens() throws IOException {
        // Files.list holds a directory handle open until its stream is closed: collect, then close.
        try (Stream<Path> files = Files.list(dir())) {
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList().stream();
        }
    }

    @Test
    void theGoldensAreThereAllOfThem() throws IOException {
        assertThat(goldens().map(p -> p.getFileName().toString())).contains(
            "all-flags.yaml", "no-flag.yaml", "after-without-key.yaml", "no-evidence.yaml",
            "refuted.yaml", "history-kept.yaml", "apply-door-refuted.yaml");
    }

    /** The parser wraps a YAML syntax failure in the same exception type as a missing field. */
    private static final String BROKEN_YAML = "invalid kind:Workflow manifest";

    @ParameterizedTest
    @MethodSource("goldens")
    void everyDraftIsRefusedForItsGapNotForBeingBrokenYaml(Path golden) throws IOException {
        String yaml = Files.readString(golden);
        assertThatThrownBy(() -> parser.parse(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining(BROKEN_YAML);
    }

    @Test
    void aCompleteDraftWithoutAKeyIsRefusedForTheDormancyNeedingIt() throws IOException {
        String yaml = Files.readString(dir().resolve("all-flags.yaml")).replaceAll("(?m)^\\s*key: .*\\R", "") + GRAPH;
        assertThat(yaml).doesNotContain("n_commande");

        assertThatThrownBy(() -> parser.parse(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.dormancy requires spec.on.key");
    }

    @Test
    void theNoFlagDraftIsRefusedForItsMissingName() throws IOException {
        String yaml = Files.readString(dir().resolve("no-flag.yaml")) + GRAPH;

        assertThatThrownBy(() -> parser.parse(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata.name");
    }

    @Test
    void theAllFlagsDraftIsAcceptedOnceTheGraphIsAdded() throws IOException {
        String yaml = Files.readString(dir().resolve("all-flags.yaml")) + GRAPH;

        WorkflowManifest m = parser.parse(yaml);

        assertThat(m.name()).isEqualTo("wf-orders");
        assertThat(m.scope()).isEqualTo("sales");
        assertThat(m.provenance()).isEqualTo("declared");
        assertThat(m.table()).isEqualTo("connector.t_commandes");
        assertThat(m.column()).isEqualTo("statut");
        assertThat(m.key()).isEqualTo("n_commande");
        assertThat(m.dormancy().after()).isEqualTo("14d");
    }

    @Test
    void aDraftWithAGapStaysRefusedEvenWithAGraph() throws IOException {
        for (String gap : new String[] {"no-flag.yaml", "after-without-key.yaml"}) {
            String yaml = Files.readString(dir().resolve(gap)) + GRAPH;
            assertThatThrownBy(() -> parser.parse(yaml)).as(gap).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowManifestParserTest {

    private final WorkflowManifestParser parser = new WorkflowManifestParser();

    private static final String VALID = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata:
          name: commande-lifecycle
        spec:
          scope: magasin
          provenance: declared
          on:
            table: connector.t_commandes_csv
            column: statut
          states:
            - code: BROUILLON
              initial: true
            - code: EN_COURS
              label: "En préparation"
              aliases: [en cours, encours, ip]
            - code: TERMINE
              aliases: [fini, ok, co]
              terminal: true
          transitions:
            - { from: [BROUILLON], to: EN_COURS }
            - { from: [EN_COURS], to: TERMINE }
        """;

    @Test
    void parses_the_nominal_manifest() {
        WorkflowManifest m = parser.parse(VALID);
        assertThat(m.name()).isEqualTo("commande-lifecycle");
        assertThat(m.scope()).isEqualTo("magasin");
        assertThat(m.provenance()).isEqualTo("declared");
        assertThat(m.table()).isEqualTo("connector.t_commandes_csv");
        assertThat(m.column()).isEqualTo("statut");
        assertThat(m.states()).hasSize(3);
        assertThat(m.initialState()).isEqualTo("BROUILLON");
        assertThat(m.states().get(2).terminal()).isTrue();
        assertThat(m.transitions()).hasSize(2);
    }

    @Test
    void rejects_wrong_kind() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("kind: Workflow", "kind: Process")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'Workflow'");
    }

    @Test
    void rejects_non_declared_provenance() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("provenance: declared", "provenance: observed")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not yet supported");
    }

    @Test
    void rejects_missing_provenance() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("  provenance: declared\n", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.provenance is required");
    }

    @Test
    void rejects_duplicate_state_codes() {
        String yaml = VALID.replace("- code: EN_COURS", "- code: BROUILLON");
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("duplicate state code");
    }

    @Test
    void rejects_ambiguous_alias_across_states() {
        String yaml = VALID.replace("aliases: [fini, ok, co]", "aliases: [fini, ip]"); // ip already on EN_COURS
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("alias 'ip'");
    }

    @Test
    void alias_uniqueness_is_checked_on_the_normalized_form() {
        String yaml = VALID.replace("aliases: [fini, ok, co]", "aliases: [fini, \" IP \"]");
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("alias");
    }

    @Test
    void rejects_alias_colliding_with_a_code() {
        String yaml = VALID.replace("aliases: [fini, ok, co]", "aliases: [brouillon]");
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("collides with state code");
    }

    @Test
    void rejects_zero_or_two_initials() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("initial: true", "initial: false")))
            .hasMessageContaining("exactly one state must be initial");
        assertThatThrownBy(() -> parser.parse(
                VALID.replace("label: \"En préparation\"", "initial: true")))
            .hasMessageContaining("exactly one state must be initial");
    }

    @Test
    void rejects_transition_to_undeclared_code() {
        String yaml = VALID.replace("to: TERMINE }", "to: LIVRE }");
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("undeclared state 'LIVRE'");
    }

    @Test
    void rejects_outgoing_transition_from_terminal_state() {
        String yaml = VALID + "    - { from: [TERMINE], to: EN_COURS }\n";
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("terminal state 'TERMINE' has an outgoing transition");
    }

    @Test
    void rejects_bad_table_and_column_identifiers() {
        assertThatThrownBy(() -> parser.parse(
                VALID.replace("connector.t_commandes_csv", "connector.t_x; DROP TABLE y")))
            .hasMessageContaining("spec.on.table");
        assertThatThrownBy(() -> parser.parse(VALID.replace("column: statut", "column: \"statut--\"")))
            .hasMessageContaining("spec.on.column");
    }

    @Test
    void transitions_are_optional_a_taxonomy_alone_is_knowledge() {
        String yaml = VALID.substring(0, VALID.indexOf("  transitions:"));
        assertThat(parser.parse(yaml).transitions()).isEmpty();
    }

    @Test
    void unknown_keys_are_reported_as_dotted_paths() {
        String yaml = VALID.replace("scope: magasin", "scope: magasin\n  scop: typo");
        assertThat(parser.unknownKeys(yaml)).containsExactly("spec.scop");
        assertThat(parser.unknownKeys(VALID)).isEmpty();
    }

    private static final String WITH_DORMANCY = VALID
        .replace("column: statut", "column: statut\n    key: num")
        .replace("provenance: declared", "provenance: declared\n  dormancy:\n    after: 30d");

    @Test
    void parses_key_and_dormancy() {
        WorkflowManifest m = parser.parse(WITH_DORMANCY);
        assertThat(m.key()).isEqualTo("num");
        assertThat(m.dormancy().after()).isEqualTo("30d");
        assertThat(m.dormancy().duration()).isEqualTo(java.time.Duration.ofDays(30));
    }

    @Test
    void key_and_dormancy_are_optional_and_absent_by_default() {
        WorkflowManifest m = parser.parse(VALID);
        assertThat(m.key()).isNull();
        assertThat(m.dormancy()).isNull();
    }

    @Test
    void dormancy_without_key_is_refused() {
        String yaml = VALID.replace("provenance: declared",
            "provenance: declared\n  dormancy:\n    after: 30d");
        assertThatThrownBy(() -> parser.parse(yaml))
            .hasMessageContaining("spec.dormancy requires spec.on.key");
    }

    @Test
    void key_must_match_the_column_grammar() {
        assertThatThrownBy(() -> parser.parse(
                WITH_DORMANCY.replace("key: num", "key: \"num; DROP\"")))
            .hasMessageContaining("spec.on.key");
    }

    @Test
    void after_grammar_is_strict() {
        for (String ok : new String[]{"30d", "2m", "12h"}) {
            assertThat(parser.parse(WITH_DORMANCY.replace("after: 30d", "after: " + ok))
                .dormancy().after()).isEqualTo(ok);
        }
        for (String bad : new String[]{"30", "30 d", "1w", "-5d", "d30"}) {
            assertThatThrownBy(() -> parser.parse(
                    WITH_DORMANCY.replace("after: 30d", "after: \"" + bad + "\"")))
                .hasMessageContaining("spec.dormancy.after");
        }
    }

    @Test
    void unknown_keys_under_dormancy_are_reported() {
        String yaml = WITH_DORMANCY.replace("after: 30d", "after: 30d\n    afterr: 1d");
        assertThat(parser.unknownKeys(yaml)).containsExactly("spec.dormancy.afterr");
    }

    @Test
    void duration_units_map_correctly() {
        assertThat(new WorkflowManifest.DormancyDef("2m").duration())
            .isEqualTo(java.time.Duration.ofMinutes(2));
        assertThat(new WorkflowManifest.DormancyDef("12h").duration())
            .isEqualTo(java.time.Duration.ofHours(12));
    }
}

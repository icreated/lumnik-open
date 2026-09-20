package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;
import java.time.Duration;
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


    /**
     * The refusals this parser can raise that no test named. Characterization, not discovery: they
     * pass on today's code by construction. Their job is to JUDGE the decomposition that follows —
     * parse() is a catalogue of rules, and a catalogue is only safe to split when every rule in it
     * is pinned. Six of the twenty-one had no test: metadata.name, spec.scope, a state without a
     * code, an empty state list, a transition missing from/to, and a transition FROM an undeclared
     * state (only `to` was covered).
     */
    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of("metadata.name must be a simple identifier",
                VALID.replace("name: commande-lifecycle", "name: Commande Lifecycle"),
                "metadata.name must be a simple identifier"),
            Arguments.of("spec.scope is required",
                VALID.replace("  scope: magasin\n", ""),
                "spec.scope is required"),
            Arguments.of("every state needs a code",
                VALID.replace("- code: BROUILLON", "- label: sans-code"),
                "every state needs a code"),
            Arguments.of("at least one state must be declared", NO_STATES,
                "spec.states must declare at least one state"),
            Arguments.of("a transition needs both from and to",
                VALID.replace("- { from: [BROUILLON], to: EN_COURS }", "- { to: EN_COURS }"),
                "every transition needs from"),
            Arguments.of("a transition cannot start from an undeclared state",
                VALID.replace("from: [BROUILLON]", "from: [INCONNU]"),
                "transition from undeclared state")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void refusesAMalformedManifest(String rule, String manifest, String expected) {
        assertThatThrownBy(() -> parser.parse(manifest))
            .as(rule)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(expected);
    }

    @Test
    void refusesABlankNameSeparatelyFromAMalformedOne() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("  name: commande-lifecycle\n", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata.name is required")
            .hasMessageNotContaining("simple identifier");
    }

    @Test
    void refusesANullBody() {
        assertThatThrownBy(() -> parser.parse((String) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid kind:Workflow manifest");
    }

    @Test
    void refusesSyntacticallyMalformedYaml() {
        assertThatThrownBy(() -> parser.parse("kind: [unclosed"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid kind:Workflow manifest");
    }

    @Test
    void anEmptyOrBlankDocumentIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> parser.parse(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'Workflow'");
        assertThatThrownBy(() -> parser.parse("   \n  \n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'Workflow'");
    }

    @Test
    void aBareDocumentMarkerIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> parser.parse("---\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'Workflow'");
    }

    private static final String NO_STATES = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata:
          name: sans-etats
        spec:
          scope: magasin
          provenance: declared
          on:
            table: connector.t_commandes_csv
            column: statut
          states: []
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

    /** A scope tag becomes the case-sensitive Keycloak role {@code scope:<tag>}, so a name no
     *  role could carry declares a lifecycle visible to unscoped users and permanently invisible
     *  to every bound one — see #577. */
    @Test
    void rejects_scope_name_no_role_could_carry() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("scope: magasin", "scope: CRM")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.scope")
            .hasMessageContaining("lowercase");
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

    /**
     * The names an ingest really lands — each one a column {@code DynamicTableWriter} creates
     * {@code quoteIdent}-ed from a source's own key. The old grammar ({@code [a-z_][a-z0-9_]*})
     * made a lifecycle undeclarable over every one of them, for a reason the integrator cannot act
     * on: the hub chose the name. See {@code docs/apps/workflows.md#names-the-source-chose}.
     */
    @ParameterizedTest(name = "a column really named {0} can be declared")
    @ValueSource(strings = {"Statut Commande", "MONTANT_€", "c_bpartner.id", "Statut", "état"})
    void a_column_the_source_named_is_declarable(String name) {
        WorkflowManifest m = parser.parse(VALID.replace("column: statut", "column: \"" + name + "\""));
        assertThat(m.column()).isEqualTo(name);
    }

    /**
     * The table half. {@code "t_" + name} is composed under {@code SqlIdentifiers.isPlainIdentifier},
     * which accepts a capital, so {@code connector.t_Orders} is a table the hub creates and the old
     * grammar refused. A fused entity view is watchable too — a taxonomy over a fused status column
     * is the same knowledge one level up (it cannot carry DORMANCY, having no {@code _ingested_at},
     * but nothing else about it differs).
     *
     * <p>The two prefixes do not share an alphabet, and that is not a detail: ingestion gates its
     * target table with {@code isPlainIdentifier} (no leading digit) BEFORE composing, while
     * {@code EntityManifestParser} admits {@code [A-Za-z0-9_]+} — a digit included — and the view
     * compiler folds it to lower case. So {@code connector.v_1customer} is a view the hub really
     * creates, and {@code connector.t_1foo} is a table it never can.
     */
    @ParameterizedTest(name = "a table the hub owns, {0}, can be watched")
    @ValueSource(strings = {"connector.t_Orders", "connector.v_customer",
                            "connector.t_x",           // the shortest name a composition produces
                            "connector.v_1customer"})  // an ENTITY may start with a digit
    void a_table_the_hub_created_can_be_declared(String table) {
        assertThat(parser.parse(VALID.replace("connector.t_commandes_csv", table)).table())
            .isEqualTo(table);
    }

    /**
     * A hostile name is no longer this door's business: it is refused by QUOTING, one layer down.
     * Measured against a real PostgreSQL in {@code WorkflowDataTruthIT} (SQLSTATE 42703, not
     * 42601) — the only place it CAN be measured, since a string assertion here would pin my own
     * escaping against itself.
     */
    @Test
    void a_hostile_name_is_no_longer_the_parsers_business() {
        String hostile = "x\"; DROP TABLE connector.t_commandes_csv; --";
        WorkflowManifest m = parser.parse(     // single-quoted YAML: the double quote is literal
            VALID.replace("column: statut", "column: '" + hostile + "'"));
        assertThat(m.column()).isEqualTo(hostile);
    }

    /**
     * The one thing the grammar did that quoting cannot — see {@code requireWatchedTable} for why a
     * privileged reader makes this a security rule rather than a style one. Structural, not
     * grammatical: the schema is the literal {@code connector} and the name carries the hub's own
     * prefix, whatever comes after it.
     *
     * <p>The three {@code connector.*} rows are the sharp ones, and they were accepted before this
     * PR: the schema holds twelve HUB-INTERNAL tables next to the ingest ones, and
     * {@code lumnik_readonly} is granted SELECT on all of them. Watching {@code connector_credential}
     * applied with a clean 200 and the data-truth report handed back up to 200 distinct values of
     * whatever column was named. Watching is for the data the hub ingested, never for the hub.
     */
    @ParameterizedTest(name = "spec.on.table {0} is refused")
    @ValueSource(strings = {"identity.app_user", "core.secret", "t_commandes_csv",
                            "connector.", "Connector.t_x", "xconnector.t_x",
                            "connector.connector_credential", "connector.schema_card",
                            "connector.saved_view",
                            // the PREFIX alone: `isPlainIdentifier("t_")` is true, so the shape
                            // check alone lets it through — the boundary my own row moved
                            "connector.t_", "connector.v_",
                            // a fused view is ALWAYS lowercased at composition
                            // (EntityViewCompiler.viewName), so a capital here can only be a typo —
                            // while `connector.t_Orders` above is a table the hub really creates
                            "connector.v_Customer",
                            // ingestion gates its target table with `plainIdentifier` BEFORE
                            // composing "t_" + it (SourceBlockValidator, KafkaConnectorValidator;
                            // CSV slugs), so no ingest table can start with a digit
                            "connector.t_1foo",
                            // a typo, always: see the javadoc on the asymmetry below
                            "connector.t_commandes_csv ", " connector.t_commandes_csv",
                            "connector.t_commandes csv", "connector.t_commandes\u00A0csv"})
    void refuses_a_table_the_hub_does_not_ingest_into(String table) {
        assertThatThrownBy(() -> parser.parse(
                VALID.replace("connector.t_commandes_csv", "\"" + table + "\"")))
            .hasMessageContaining("spec.on.table");
    }

    @Test
    void refuses_a_column_that_names_nothing() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("column: statut", "column: \"   \"")))
            .hasMessageContaining("spec.on.column");
        assertThatThrownBy(() -> parser.parse(VALID.replace("    column: statut\n", "")))
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
        assertThat(m.dormancy().duration()).isEqualTo(Duration.ofDays(30));
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

    /**
     * A fused view can carry a TAXONOMY but never a DORMANCY, and the door has to say so — I wrote
     * that constraint into the page in this very PR and left the parser accepting it.
     *
     * <p>The sweep's SQL is per-DOCUMENT: it picks the latest version per business key with
     * {@code DISTINCT ON (key) … ORDER BY key, _ingested_at DESC, id DESC} and scopes rows with
     * {@code tenant_id = ?}. An {@code EntityViewCompiler} view projects the canonical columns plus
     * {@code _conflicts} — none of those three. So a dormancy declared on one applies with a clean
     * 200 and then fails 42703 on every tick, swallowed by the per-workflow net: an alert the
     * integrator is waiting for that can never arrive, with nothing to look at.
     *
     * <p>Unlike {@code tableMissing} or {@code keyColumnMissing}, this condition can never resolve
     * — it is what a view IS — so it is refused rather than reported.
     */
    @Test
    void dormancy_on_a_fused_view_is_refused_because_a_view_has_no_versions() {
        assertThatThrownBy(() -> parser.parse(
                WITH_DORMANCY.replace("connector.t_commandes_csv", "connector.v_commandes")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.dormancy");
    }

    /** …and the same view without dormancy stays declarable: the taxonomy is the point. */
    @Test
    void a_taxonomy_on_a_fused_view_is_still_declarable() {
        assertThat(parser.parse(VALID.replace("connector.t_commandes_csv", "connector.v_commandes"))
            .dormancy()).isNull();
    }

    /**
     * The asymmetry this PR turns on, and the reason whitespace is refused above but kept here:
     * <b>who chose the name</b>.
     *
     * <p>The TABLE is composed by the hub — {@code "t_" + name} under
     * {@code SqlIdentifiers.isPlainIdentifier}, or {@code "v_" + name} under the entity parser's
     * {@code [A-Za-z0-9_]+}. Neither alphabet holds a space, so whitespace in {@code spec.on.table}
     * cannot be a real name: it is a typo, and accepting it reports {@code tableMissing}, which the
     * page phrases as the LEGITIMATE "declared before ingesting" case. The wrong errand.
     *
     * <p>The COLUMN is copied from the source. A kafka JSON key really can end in a space, and
     * {@code DynamicTableWriter} creates that column verbatim — so stripping or refusing it here
     * would be this door second-guessing a name it did not choose, which is the whole defect this
     * PR exists to remove. A column that does not resolve is honest anyway: the report comes back
     * {@code checkUnavailable} carrying SQLSTATE 42703, not a "not yet ingested" flag.
     */
    @Test
    void a_column_keeps_its_whitespace_because_the_source_chose_it_and_the_table_does_not() {
        assertThat(parser.parse(VALID.replace("column: statut", "column: \"Statut Commande \""))
            .column()).isEqualTo("Statut Commande ");
        assertThatThrownBy(() -> parser.parse(
                VALID.replace("connector.t_commandes_csv", "\"connector.t_commandes_csv \"")))
            .hasMessageContaining("spec.on.table");
    }

    /**
     * The one character a PostgreSQL identifier can NEVER hold — and the whole of that set.
     *
     * <p>Everything else survives quoting, which is why this door stopped filtering names. NUL does
     * not: PostgreSQL cannot store it in any text value, so no column can ever carry it, and a name
     * containing one can never resolve. By the same rule this PR applies to the fused-view dormancy,
     * a condition that can never resolve is a REFUSAL, not a report — the alternative is a manifest
     * that applies with a clean 200 and a probe that fails forever on a character the integrator
     * cannot see in their own YAML.
     *
     * <p>Not a grammar coming back: the complement of "any name at all" has exactly one member.
     * It reaches us only through YAML's {@code \0} escape — a literal NUL is not valid in a YAML
     * stream at all.
     */
    @Test
    void a_name_carrying_the_one_character_postgres_cannot_hold_is_refused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("column: statut", "column: \"a\\0b\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.on.column");
        assertThatThrownBy(() -> parser.parse(WITH_DORMANCY.replace("key: num", "key: \"n\\0m\"")))
            .hasMessageContaining("spec.on.key");
    }

    /** The key is a column like any other — same door, same widening, same single refusal left. */
    @Test
    void the_key_is_a_column_name_too() {
        assertThat(parser.parse(WITH_DORMANCY.replace("key: num", "key: \"N° Commande\"")).key())
            .isEqualTo("N° Commande");
        assertThatThrownBy(() -> parser.parse(WITH_DORMANCY.replace("key: num", "key: \"  \"")))
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
            .isEqualTo(Duration.ofMinutes(2));
        assertThat(new WorkflowManifest.DormancyDef("12h").duration())
            .isEqualTo(Duration.ofHours(12));
    }
}

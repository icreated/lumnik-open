package io.lumnik.hub.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.workflow.WorkflowManifestParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the catalogue CONSISTENT with the product — every kind, field, page, anchor and test it
 * cites exists — never that a recommendation is right (spec section 4). Anchored on production
 * code: the honoured surface is the parser's own, the very list a 400 prints.
 */
class ProposalCatalogueInventoryTest {

    /** Kinds a rule may propose. Making a kind proposable is one explicit entry here. */
    private static final Map<String, Function<JsonNode, List<String>>> SURFACES = Map.of(
        "Workflow", new WorkflowManifestParser()::unknownKeys);

    private static final Set<String> VERIFIERS = Set.of("workflow-data-truth", "workflow-apply-door", "none");
    private static final Set<String> QUESTION_KINDS = Set.of("choice", "yes-no-unknown");
    private static final Set<String> LANGUAGES = Set.of("fr", "en");

    private final Catalogue catalogue = Catalogue.load();

    @Test
    void every_proposed_path_is_honoured_by_its_kind() {
        for (Catalogue.Rule rule : catalogue.rules()) {
            assertThat(unknownKeysOf(rule.proposes().kind(), rule.proposes().uses()))
                .as("rule %s@%d proposes paths %s does not honour", rule.id(), rule.version(), rule.proposes().kind())
                .isEmpty();
        }
    }

    @Test
    void the_surface_check_is_not_blind() {
        assertThat(unknownKeysOf("Workflow", List.of("spec.after"))).containsExactly("spec.after");
        assertThatThrownBy(() -> unknownKeysOf("View", List.of("spec.table")))
            .hasMessageContaining("no known surface for kind View");
    }

    @Test
    void every_doc_anchor_and_behaviour_test_exists() {
        for (Catalogue.Rule rule : catalogue.rules()) {
            Path page = Repo.root().resolve(rule.grounds().doc().page());
            assertThat(page).as("rule %s cites a page", rule.id()).isRegularFile();
            assertThat(DocAnchors.of(page)).as("anchors of %s", page).contains(rule.grounds().doc().anchor());
            assertThat(Repo.root().resolve(rule.grounds().behaviour())).as("rule %s cites a test", rule.id()).isRegularFile();
        }
    }

    @Test
    void the_slug_matches_mkdocs_and_skips_fences() throws Exception {
        assertThat(DocAnchors.slug("Dormancy — the memory of the breathing"))
            .isEqualTo("dormancy-the-memory-of-the-breathing");
        Path md = Files.createTempFile("anchors", ".md");
        Files.writeString(md, "# Real heading\n```bash\n# not a heading\n```\n");
        assertThat(DocAnchors.of(md)).containsExactly("real-heading");
    }

    @Test
    void the_reader_refuses_a_duplicate_key_and_trailing_content() {
        String valid = "{\"catalogue\": \"1\", \"questions\": [], \"rules\": []}";
        assertThat(Catalogue.parse(valid).catalogue()).isEqualTo("1");
        assertThatThrownBy(() -> Catalogue.parse("{\"catalogue\": \"1\", \"catalogue\": \"1\", \"questions\": [], \"rules\": []}"))
            .hasMessageContaining("Duplicate field 'catalogue'");
        assertThatThrownBy(() -> Catalogue.parse(valid + " {}"))
            .hasMessageContaining("Trailing token");
    }

    @Test
    void questions_and_conditions_reference_what_is_declared() {
        Map<String, Catalogue.Question> questions = new HashMap<>();
        for (Catalogue.Question q : catalogue.questions()) {
            assertThat(questions.put(q.id(), q)).as("duplicate question %s", q.id()).isNull();
            assertThat(QUESTION_KINDS).as("kind of question %s", q.id()).contains(q.kind());
            assertThat(q.asked().keySet()).as("languages of question %s", q.id()).isEqualTo(LANGUAGES);
            if (q.kind().equals("choice")) {
                assertThat(q.options()).as("options of %s", q.id()).isNotEmpty();
                q.options().forEach(o -> assertThat(o.label().keySet()).as("option %s", o.id()).isEqualTo(LANGUAGES));
            } else {
                assertThat(q.options()).as("a yes-no-unknown question declares no options: %s", q.id()).isNull();
            }
        }
        Set<String> ruleKeys = new HashSet<>();
        for (Catalogue.Rule rule : catalogue.rules()) {
            assertThat(ruleKeys.add(rule.id() + "@" + rule.version())).as("duplicate rule %s@%d", rule.id(), rule.version()).isTrue();
            Catalogue.Question when = questions.get(rule.when().answer());
            assertThat(when).as("rule %s: when.answer", rule.id()).isNotNull();
            assertThat(when.options()).extracting(Catalogue.Option::id).as("rule %s: when.is", rule.id()).contains(rule.when().is());
            assertThat(rule.meaning().keySet()).as("languages of rule %s", rule.id()).isEqualTo(LANGUAGES);
            Set<String> needs = new HashSet<>();
            for (Catalogue.Condition c : rule.conditions()) {
                assertThat(needs.add(c.needs())).as("rule %s: duplicate condition %s", rule.id(), c.needs()).isTrue();
                assertThat(VERIFIERS).as("verifier of %s", c.needs()).contains(c.verifier());
                assertThat(c.label().keySet()).as("languages of %s", c.needs()).isEqualTo(LANGUAGES);
                if (c.note() != null) assertThat(c.note().keySet()).as("note of %s", c.needs()).isEqualTo(LANGUAGES);
                if (c.askedBy() != null) {
                    Catalogue.Question q = questions.get(c.askedBy());
                    assertThat(q).as("%s asked-by", c.needs()).isNotNull();
                    // the site's derivation table only knows yes / no / unknown / unanswered
                    assertThat(q.kind()).as("%s must be backed by a yes-no-unknown question", c.needs()).isEqualTo("yes-no-unknown");
                }
            }
        }
    }

    private static List<String> unknownKeysOf(String kind, List<String> paths) {
        Function<JsonNode, List<String>> surface = SURFACES.get(kind);
        if (surface == null) throw new IllegalArgumentException("no known surface for kind " + kind
            + " — add it to SURFACES to make it proposable");
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("apiVersion", "apps.lumnik.io/v1").put("kind", kind).putObject("metadata").put("name", "inventory");
        for (String path : paths) {
            assertThat(path).as("a proposed path is a dotted object path").matches("[a-z][A-Za-z]*(\\.[a-z][A-Za-z]*)*");
            String[] segments = path.split("\\.");
            ObjectNode node = root;
            for (int i = 0; i < segments.length - 1; i++) {
                node = node.has(segments[i]) ? (ObjectNode) node.get(segments[i]) : node.putObject(segments[i]);
            }
            node.put(segments[segments.length - 1], "x");
        }
        return surface.apply(root);
    }
}

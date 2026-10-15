package io.lumnik.hub.workflow;

import io.lumnik.hub.testsupport.LogProbe;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two reads of the stored manifests: {@code all} serves and refuses loudly, {@code allParsable}
 * is what a background pass reads and steps over a row the parser refuses (#807).
 */
class WorkflowRegistryTest {

    /** Present ONLY in the garbled YAML — if it shows up in the log, the YAML got there. */
    private static final String YAML_ONLY = "only-in-the-yaml-9f3a";

    private static final String GOOD = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: good }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_good, column: statut, key: num }
          dormancy: { after: 1m }
          states:
            - { code: EN_COURS, initial: true }
            - { code: TERMINE, terminal: true }
        """;

    /** Unterminated flow sequence: SnakeYAML's refusal quotes the offending line, marker included. */
    private static final String GARBLED = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: broken }
        spec: [%s
        """.formatted(YAML_ONLY);

    private static WorkflowRegistry registryOver(WorkflowManifestStore.StoredWorkflow... rows) {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.store = new WorkflowManifestStore() {
            @Override public List<StoredWorkflow> listByTenant(long tenantId) {
                return List.of(rows);
            }
        };
        return registry;
    }

    private static WorkflowManifestStore.StoredWorkflow row(String name, boolean enabled, String yaml) {
        return new WorkflowManifestStore.StoredWorkflow(name, "demo", "connector.t_x", "statut", enabled, yaml);
    }

    @Test
    void all_still_refuses_a_row_the_parser_refuses() {
        WorkflowRegistry registry = registryOver(row("a-broken", true, GARBLED), row("good", true, GOOD));

        assertThatThrownBy(() -> registry.all(1L)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void allParsable_steps_over_the_refused_row_and_keeps_the_order_of_the_rest() {
        WorkflowRegistry registry = registryOver(
            row("a-broken", true, GARBLED),
            row("good", true, GOOD),
            row("z-off", false, GARBLED));

        assertThat(registry.allParsable(1L)).extracting(WorkflowGraph::name).containsExactly("good");
    }

    @Test
    void allParsable_names_the_row_and_the_failure_class_and_never_the_yaml() {
        WorkflowRegistry registry = registryOver(row("a-broken", true, GARBLED), row("good", true, GOOD));

        // A probe that sees nothing passes every assertion about what was not logged: the parser
        // must really quote the marker, or the absence below proves nothing.
        assertThatThrownBy(() -> new WorkflowManifestParser().parse(GARBLED))
            .hasStackTraceContaining(YAML_ONLY);

        String rendered = renderedLog(() -> registry.allParsable(1L));

        assertThat(rendered).contains("a-broken").contains("IllegalArgumentException");
        assertThat(rendered).doesNotContain(YAML_ONLY);
    }

    /** Every record the registry's logger wrote: message, plus the whole stack of anything thrown. */
    private static String renderedLog(Runnable action) {
        List<String> out = LogProbe.capture(WorkflowRegistry.class, action).stream().map(l -> {
            StringWriter text = new StringWriter();
            text.append(l.level().toString()).append(' ').append(l.message());
            if (l.thrown() != null) l.thrown().printStackTrace(new PrintWriter(text));
            return text.toString();
        }).toList();
        assertThat(out).as("the refusal was logged at all").isNotEmpty();
        return String.join("\n", out);
    }
}

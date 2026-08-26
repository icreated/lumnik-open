package io.lumnik.hub.process;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessManifestParserTest {

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Process
        metadata:
          name: gold-client-arrive
        spec:
          scope: clients
          on:
            event: data.row.changed
            table: connector.t_cust_csv
          when:
            - segment=GOLD
          emit:
            type: decision.gold-client-arrive
            message: "Nouveau client GOLD"
        """;

    @Test
    void parsesANotifyProcess() {
        ProcessManifest m = new ProcessManifestParser().parse(YAML);
        assertThat(m.name()).isEqualTo("gold-client-arrive");
        assertThat(m.scope()).isEqualTo("clients");
        assertThat(m.onEvent()).isEqualTo("data.row.changed");
        assertThat(m.onTable()).isEqualTo("connector.t_cust_csv");
        assertThat(m.when()).containsExactly("segment=GOLD");
        assertThat(m.emitType()).isEqualTo("decision.gold-client-arrive");
        assertThat(m.emitMessage()).isEqualTo("Nouveau client GOLD");
    }

    @Test
    void refusesWrongKind() {
        assertThatThrownBy(() -> new ProcessManifestParser().parse(YAML.replace("kind: Process", "kind: View")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Process");
    }

    @Test
    void refusesAnUnsupportedTriggerEvent() {
        assertThatThrownBy(() -> new ProcessManifestParser().parse(
                YAML.replace("data.row.changed", "connector.run.completed")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("data.row.changed");
    }

    @Test
    void refusesAnEmitTypeOutsideTheDecisionTaxonomy() {
        assertThatThrownBy(() -> new ProcessManifestParser().parse(
                YAML.replace("decision.gold-client-arrive", "platform.secret.read")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decision.");
    }

    @Test
    void refusesMissingWhenAndMissingMessage() {
        assertThatThrownBy(() -> new ProcessManifestParser().parse(
                YAML.replace("  when:\n    - segment=GOLD\n", "")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("when");
        assertThatThrownBy(() -> new ProcessManifestParser().parse(
                YAML.replace("message: \"Nouveau client GOLD\"", "")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("message");
    }

    @Test
    void refusesANonSimpleName() {
        assertThatThrownBy(() -> new ProcessManifestParser().parse(
                YAML.replace("name: gold-client-arrive", "name: \"gold client!\"")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }
}

package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewManifestParserTest {
    @Test
    void parsesAListViewManifest() {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata:
              name: clients-desk
            spec:
              scope: clients
              table: connector.v_customer
              title: Clients
              columns: [customer_code, name, city]
            """;
        ViewManifest m = new ViewManifestParser().parse(yaml);
        assertThat(m.name()).isEqualTo("clients-desk");
        assertThat(m.scope()).isEqualTo("clients");
        assertThat(m.table()).isEqualTo("connector.v_customer");
        assertThat(m.title()).isEqualTo("Clients");
        assertThat(m.columns()).containsExactly("customer_code", "name", "city");
    }

    @Test
    void scopeIsNormalizedAtTheThreshold() {
        // Accidental whitespace in spec.scope must not leak into authorization
        // (a bound user would get a lying 403) or into the SchemaCard lookup.
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata:
              name: clients-desk
            spec:
              scope: " clients "
              columns: [name]
            """;
        assertThat(new ViewManifestParser().parse(yaml).scope()).isEqualTo("clients");
    }

    @Test
    void refusesAManifestWhoseKindIsNotView() {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Entity
            metadata:
              name: clients-desk
            spec:
              scope: clients
              columns: [name]
            """;
        assertThatThrownBy(() -> new ViewManifestParser().parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'View'");
    }

    @Test
    void refusesAManifestWithNoName() {
        String yaml = """
            kind: View
            spec:
              scope: clients
              columns: [name]
            """;
        assertThatThrownBy(() -> new ViewManifestParser().parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void refusesAManifestWithNoScope() {
        String yaml = """
            kind: View
            metadata:
              name: clients-desk
            spec:
              columns: [name]
            """;
        assertThatThrownBy(() -> new ViewManifestParser().parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void refusesAManifestWithNoColumns() {
        String yaml = """
            kind: View
            metadata:
              name: clients-desk
            spec:
              scope: clients
            """;
        assertThatThrownBy(() -> new ViewManifestParser().parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
    }
}

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

    /** #1489: YAML 1.1's yes/no/on/off are text through the app-ladder surface, as in YAML 1.2. */
    @Test
    void columnsNamedLikeYaml11BooleansAreColumnNames() {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata:
              name: orders-desk
            spec:
              scope: orders
              table: connector.v_order
              columns: [no, on, OFF, Yes]
            """;
        assertThat(new ViewManifestParser().parse(yaml).columns()).containsExactly("no", "on", "OFF", "Yes");
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

    /** A scope tag becomes the case-sensitive Keycloak role {@code scope:<tag>}, so a name no
     *  role could carry declares a view visible to unscoped users and permanently invisible to
     *  every bound one — see #577. */
    @Test
    void refusesAScopeNameNoRoleCouldCarry() {
        String yaml = """
            kind: View
            metadata:
              name: clients-desk
            spec:
              scope: CRM
              columns: [name]
            """;
        assertThatThrownBy(() -> new ViewManifestParser().parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec.scope")
                .hasMessageContaining("lowercase");
    }

    @Test
    void refusesANullBody() {
        assertThatThrownBy(() -> new ViewManifestParser().parse((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid kind:View manifest");
    }

    @Test
    void refusesSyntacticallyMalformedYaml() {
        assertThatThrownBy(() -> new ViewManifestParser().parse("kind: [unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid kind:View manifest");
    }

    /** YAMLMapper.readTree("") / readTree(blank) return a MissingNode without throwing — verified
     *  empirically against this project's actual Jackson 2.21.5/SnakeYAML 2.5. The ordinary
     *  "kind must be 'View'" check is what refuses these, not the broad parse-failure catch. */
    @Test
    void anEmptyOrBlankDocumentIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> new ViewManifestParser().parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'View'");
        assertThatThrownBy(() -> new ViewManifestParser().parse("   \n  \n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'View'");
    }

    /** A bare "---" returns a NullNode, same reasoning as the empty-document case above. */
    @Test
    void aBareDocumentMarkerIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> new ViewManifestParser().parse("---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'View'");
    }
}

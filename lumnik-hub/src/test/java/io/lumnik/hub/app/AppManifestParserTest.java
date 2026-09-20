package io.lumnik.hub.app;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppManifestParserTest {

    private final AppManifestParser parser = new AppManifestParser();

    private static final String FULL = """
        kind: App
        apiVersion: apps.lumnik.io/v1
        metadata:
          name: lumilait
        spec:
          title: Lumilait
          scope: lumilait
          icon: generic
          theme:
            accentColor: "#4fd1a5"
            backgroundColor: "#f4f1ea"
          default: false
        """;

    private static final String MINIMAL = """
        kind: App
        metadata:
          name: lumilait
        spec:
          title: Lumilait
          scope: lumilait
          icon: generic
        """;

    @Test
    void parsesEveryDeclaredField() {
        AppManifest m = parser.parse(FULL);
        assertThat(m.name()).isEqualTo("lumilait");
        assertThat(m.title()).isEqualTo("Lumilait");
        assertThat(m.scope()).isEqualTo("lumilait");
        assertThat(m.icon()).isEqualTo("generic");
        assertThat(m.accentColor()).isEqualTo("#4fd1a5");
        assertThat(m.backgroundColor()).isEqualTo("#f4f1ea");
        assertThat(m.isDefault()).isFalse();
    }

    @Test
    void fillsInTheThemeSoTheStoredColumnsAreNeverNull() {
        AppManifest m = parser.parse(MINIMAL);
        assertThat(m.accentColor()).as("today's --accent").isEqualTo("#8b7ff0");
        assertThat(m.backgroundColor()).as("today's --bg").isEqualTo("#14121f");
        assertThat(m.isDefault()).isFalse();
    }

    @Test
    void normalisesTheScopeAtTheThreshold() {
        assertThat(parser.parse(MINIMAL.replace("scope: lumilait", "scope: \"  lumilait  \"")).scope())
            .as("stray whitespace must not leak into authorization or the registry key")
            .isEqualTo("lumilait");
    }

    @Test
    void refusesAnotherKind() {
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("kind: App", "kind: View")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'App'");
    }

    @Test
    void refusesAMissingRequiredField() {
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("  name: lumilait\n", "")))
            .hasMessageContaining("metadata.name is required");
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("  title: Lumilait\n", "")))
            .hasMessageContaining("spec.title is required");
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("  scope: lumilait\n", "")))
            .hasMessageContaining("spec.scope is required");
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("  icon: generic\n", "")))
            .hasMessageContaining("spec.icon is required");
    }

    @Test
    void refusesAnIconOutsideTheCuratedSet() {
        // The refusal must NAME the whole curated set, sorted — an integrator reads this sentence
        // instead of the source. Derived from ICONS so the two cannot drift apart.
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("icon: generic", "icon: unicorn")))
            .hasMessageContaining("spec.icon must be one of")
            .hasMessageContaining(new TreeSet<>(AppManifestParser.ICONS).toString());
    }

    /** A scope tag becomes the case-sensitive Keycloak role {@code scope:<tag>}, so a name no role
     *  can carry declares an app that is visible to unscoped users and invisible to every bound
     *  one, forever — and with {@code default: true}, one a bare /app/ lands on. */
    @Test
    void refusesAScopeNameNoRoleCouldCarry() {
        assertThatThrownBy(() -> parser.parse(MINIMAL.replace("scope: lumilait", "scope: CRM")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.scope")
            .hasMessageContaining("lowercase");
        assertThatThrownBy(() -> parser.parse(
                MINIMAL.replace("scope: lumilait", "scope: \"Ventes 2026\"")))
            .hasMessageContaining("spec.scope");
    }

    @Test
    void acceptsEveryScopeTheShippedRowsAndTestsUse() {
        // V48's two seeds and every scope the ITs declare — the rule must not refuse what this
        // tree already writes.
        for (String tag : List.of("clients", "stock", "salon", "crise", "lumilait")) {
            assertThat(parser.parse(MINIMAL.replace("scope: lumilait", "scope: " + tag)).scope())
                .isEqualTo(tag);
        }
    }

    @Test
    void refusesAColourThatIsNotSixHexDigits() {
        assertThatThrownBy(() -> parser.parse(
                MINIMAL + "  theme:\n    accentColor: \"green\"\n"))
            .hasMessageContaining("spec.theme.accentColor")
            .hasMessageContaining("#4fd1a5");
        assertThatThrownBy(() -> parser.parse(
                MINIMAL + "  theme:\n    backgroundColor: \"#fff\"\n"))
            .hasMessageContaining("spec.theme.backgroundColor");
    }

    @Test
    void refusesAnAccentTheChatBubbleWouldSwallow() {
        assertThatThrownBy(() -> parser.parse(
                MINIMAL + "  theme:\n    accentColor: \"#101010\"\n"))
            .as("--accent is the .bubble.me BACKGROUND, under fixed #17122e text")
            .hasMessageContaining("does not have enough contrast")
            .hasMessageContaining("#17122e")
            .hasMessageContaining("4.5");
    }

    @Test
    void acceptsTheAccentItAlreadyShipsWith() {
        assertThat(parser.parse(MINIMAL + "  theme:\n    accentColor: \"#8b7ff0\"\n").accentColor())
            .as("V48 seeds this value — the rule must not refuse what the migration writes")
            .isEqualTo("#8b7ff0");
    }

    @Test
    void namesTheKeysItDoesNotHonour() {
        assertThat(parser.unknownKeys(FULL)).isEmpty();
        assertThat(parser.unknownKeys(FULL.replace("  title: Lumilait", "  titel: Lumilait")))
            .containsExactly("spec.titel");
        assertThat(parser.unknownKeys(FULL.replace("    accentColor:", "    accentColour:")))
            .containsExactly("spec.theme.accentColour");
        assertThat(parser.unknownKeys("kind: [unclosed"))
            .as("a manifest that does not parse is parse()'s error to raise, not this check's")
            .isEmpty();
    }

    @Test
    void theHonoredSurfaceIsDerivedFromTheInventory() {
        String surface = AppManifestParser.honoredSurface();
        assertThat(surface).contains("apiVersion").contains("theme").contains("default")
            .contains("accentColor").contains("backgroundColor");
    }

    @Test
    void refusesANullBody() {
        assertThatThrownBy(() -> parser.parse((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid kind:App manifest");
    }

    @Test
    void refusesSyntacticallyMalformedYaml() {
        assertThatThrownBy(() -> parser.parse("kind: [unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid kind:App manifest");
    }

    @Test
    void anEmptyOrBlankDocumentIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'App'");
        assertThatThrownBy(() -> parser.parse("   \n  \n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'App'");
    }

    @Test
    void aBareDocumentMarkerIsRefusedAsAWrongKindNotAsUnparsable() {
        assertThatThrownBy(() -> parser.parse("---\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be 'App'");
    }
}

package io.lumnik.hub.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestSurfaceTest {

    @Test
    void honoredSurfaceJoinsGroupsInDeclarationOrderWithSortedKeys() {
        ManifestSurface surface = ManifestSurface.builder()
            .group("", "root", Set.of("kind", "apiVersion"))
            .group("metadata", "metadata", Set.of("name"))
            .build();

        assertEquals("root {apiVersion, kind}, metadata {name}", surface.honoredSurface());
    }

    /** WorkflowManifestParser's spec.states[] walks that path but PRINTS as "states[]" — the
     *  label is declared, never derived from the walk path. */
    @Test
    void aGroupsLabelCanDifferFromItsWalkPath() throws Exception {
        ManifestSurface surface = ManifestSurface.builder()
            .group("spec.states[]", "states[]", Set.of("code"))
            .build();

        assertEquals("states[] {code}", surface.honoredSurface());

        JsonNode root = surface.readTree("""
            spec:
              states:
                - code: A
                  bogus: y
            """);
        assertEquals(List.of("spec.states[0].bogus"), surface.unknownKeys(root));
    }

    @Test
    void unknownKeysDelegatesToManifestKeysWalkingEveryDeclaredGroup() throws Exception {
        ManifestSurface surface = ManifestSurface.builder()
            .group("", "root", Set.of("kind", "metadata"))
            .group("metadata", "metadata", Set.of("name"))
            .build();

        JsonNode root = surface.readTree("""
            kind: Test
            metadata:
              name: x
              bogus: y
            """);
        assertEquals(List.of("metadata.bogus"), surface.unknownKeys(root));
    }

    @Test
    void readTreeThrowsOnMalformedYaml() {
        ManifestSurface surface = ManifestSurface.builder().build();
        assertThrows(JsonProcessingException.class, () -> surface.readTree("kind: [unclosed"));
    }
}

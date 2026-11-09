package io.lumnik.hub.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reviewers of #1847: a miss reloads every stored Entity as one read. One unreadable row must not hide the Entity asked
 * for — but only an unreadable ROW is forgiven: a failure to reach the table is the server's, and is not an answer of
 * "no such Entity"; and the single-row fallback keeps the registry's invariant that a row's name is its YAML's name.
 */
class EntityRegistryReloadTest {

    private static String yaml(String name, String column) {
        return "kind: Entity\nname: " + name + "\nmatch: [" + column + "]\nsources:\n  - table: t_a\n    map: { " + column + ": " + column + " }\n";
    }

    @SuppressWarnings("unchecked")
    private static EntityRegistry over(List<EntityRegistry.Row> rows) {
        Instance<EntityManifestParser> parser = mock(Instance.class);
        when(parser.get()).thenReturn(new EntityManifestParser());
        EntityRegistry registry = new EntityRegistry() {
            @Override List<Row> readRows() { return rows; }
        };
        registry.parserInstance = parser;
        return registry;
    }

    @Test
    void anUnreadableSiblingRowDoesNotHideTheEntityAskedFor() {
        EntityRegistry registry = over(List.of(
            new EntityRegistry.Row("broken", "kind: Entity\nname: [not closed"),
            new EntityRegistry.Row("good", yaml("Good", "code"))));

        assertThat(registry.findReloadingIfMissing("good")).isNotNull();
        assertThat(registry.findReloadingIfMissing("broken")).isNull();
    }

    @Test
    void aFailureToReachTheTableIsNotAnAnswerOfNoSuchEntity() {
        EntityRegistry registry = new EntityRegistry() {
            @Override List<Row> readRows() { throw new IllegalStateException("pool exhausted"); }
        };

        assertThatThrownBy(() -> registry.findReloadingIfMissing("good")).isInstanceOf(IllegalStateException.class);
    }

    /** A damaged row for one name whose YAML declares another valid Entity must not overwrite that other entry. */
    @Test
    void theFallbackKeepsTheRowNameEqualToTheYamlName() {
        EntityRegistry registry = over(List.of(
            new EntityRegistry.Row("isogood", yaml("Other", "wrong")),
            new EntityRegistry.Row("broken", "kind: Entity\nname: [not closed")));
        EntityManifest other = new EntityManifestParser().parse(yaml("Other", "code"));
        registry.put(other);

        assertThat(registry.findReloadingIfMissing("isogood")).isNull();
        assertThat(registry.find("other")).isSameAs(other);
    }
}

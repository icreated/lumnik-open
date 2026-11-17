package io.lumnik.hub.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            new EntityRegistry.Row("broken", "kind: Entity\nname: [not closed", null),
            new EntityRegistry.Row("good", yaml("Good", "code"), null)));

        assertThat(registry.findReloadingIfMissingHubwide("good")).isNotNull();
        assertThat(registry.findReloadingIfMissingHubwide("broken")).isNull();
    }

    @Test
    void aFailureToReachTheTableIsNotAnAnswerOfNoSuchEntity() {
        EntityRegistry registry = new EntityRegistry() {
            @Override List<Row> readRows() { throw new IllegalStateException("pool exhausted"); }
        };

        assertThatThrownBy(() -> registry.findReloadingIfMissingHubwide("good")).isInstanceOf(IllegalStateException.class);
    }

    /** A damaged row for one name whose YAML declares another valid Entity must not overwrite that other entry. */
    @Test
    void theFallbackKeepsTheRowNameEqualToTheYamlName() {
        EntityRegistry registry = over(List.of(
            new EntityRegistry.Row("isogood", yaml("Other", "wrong"), null),
            new EntityRegistry.Row("broken", "kind: Entity\nname: [not closed", null)));
        EntityManifest other = new EntityManifestParser().parse(yaml("Other", "code"));
        registry.put(other, null);

        assertThat(registry.findReloadingIfMissingHubwide("isogood")).isNull();
        assertThat(registry.findHubwide("other")).isSameAs(other);
    }

    /** #2050: a name held for another owner is re-read alone; an unreadable row is no Entity, never a 500. */
    @Test
    void aNameHeldForAnotherOwnerIsReReadAlone() {
        List<EntityRegistry.Row> table = new ArrayList<>(List.of(new EntityRegistry.Row("x", yaml("X", "code"), 2L)));
        EntityRegistry registry = overOne(table);
        registry.loadFromDb();
        assertThat(registry.find(1L, "x")).as("tenant 2's").isNull();

        table.set(0, new EntityRegistry.Row("x", "kind: Entity\nname: [not closed", 1L));
        assertThat(registry.findReloadingIfMissing(1L, "x")).as("unreadable: absent, not a failure").isNull();

        table.set(0, new EntityRegistry.Row("x", yaml("X", "code"), 1L));
        assertThat(registry.findReloadingIfMissing(1L, "x")).as("deleted, then re-applied by tenant 1").isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static EntityRegistry overOne(List<EntityRegistry.Row> table) {
        Instance<EntityManifestParser> parser = mock(Instance.class);
        when(parser.get()).thenReturn(new EntityManifestParser());
        EntityRegistry registry = new EntityRegistry() {
            @Override List<Row> readRows() { return table; }
            @Override String fingerprint() { return ""; }
            @Override Optional<Row> readRow(String key) {
                return table.stream().filter(r -> r.name().equals(key)).findFirst();
            }
        };
        registry.parserInstance = parser;
        return registry;
    }
}

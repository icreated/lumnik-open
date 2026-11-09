package io.lumnik.hub.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.UnreadableEntityException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reviewers of #1847: only a stored definition this version cannot READ is the caller's to fix (a refusal); a failure to
 * reach the row is the server's, and must stay one.
 */
class MatchIndexesStoredEntityTest {

    private static MatchIndexes over(EntityRegistry registry) {
        MatchIndexes indexes = new MatchIndexes();
        indexes.entities = registry;
        return indexes;
    }

    @Test
    void aDefinitionThatCannotBeParsedIsTheUnreadableOnesException() {
        MatchIndexes indexes = over(new EntityRegistry() {
            @Override public Optional<Stored> stored(String name) { return Optional.of(new Stored("kind: Entity\nname: [x", java.util.List.of())); }
        });

        assertThatThrownBy(() -> indexes.storedEntity("connector.v_foo")).isInstanceOf(UnreadableEntityException.class);
    }

    @Test
    void aFailureToReachTheRowStaysTheServersOwn() {
        MatchIndexes indexes = over(new EntityRegistry() {
            @Override public Optional<Stored> stored(String name) { throw new IllegalStateException("pool exhausted"); }
        });

        assertThatThrownBy(() -> indexes.storedEntity("connector.v_foo"))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(UnreadableEntityException.class);
    }

    @Test
    void noEntityUnderThatViewIsEmpty() {
        assertThat(over(new EntityRegistry() {
            @Override public Optional<Stored> stored(String name) { return Optional.empty(); }
        }).storedEntity("connector.v_foo")).isEmpty();
    }
}

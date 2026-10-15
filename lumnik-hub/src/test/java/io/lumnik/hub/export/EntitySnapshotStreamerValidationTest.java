package io.lumnik.hub.export;

import io.lumnik.hub.entity.EntityManifest;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EntitySnapshotStreamerValidationTest {

    private static EntityManifest entity(String... canonicalCols) {
        return new EntityManifest("Entity", "ValidationFixture", List.of(canonicalCols[0]),
            List.of(new EntityManifest.Source("t_fixture", mapOf(canonicalCols))));
    }

    private static Map<String, String> mapOf(String... canonicalCols) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : canonicalCols) m.put(c, c); // physical == canonical, fine for this fixture
        return m;
    }

    @Test
    void emptyColumnsIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of(), List.of("code"), entity("code")));
    }

    @Test
    void emptyOrderColumnsIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of("code"), List.of(), entity("code")));
    }

    @Test
    void duplicateColumnsIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of("code", "code"), List.of("code"), entity("code")));
    }

    @Test
    void duplicateOrderColumnsIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of("code"), List.of("code", "code"), entity("code")));
    }

    /** The invariant added during design review: a hidden sort key would let two rows the
     *  emitted CSV shows as identical still not be the ones the sort couldn't tell apart. */
    @Test
    void orderColumnNotInProjectionIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of("code"), List.of("code", "hidden"), entity("code", "hidden")));
    }

    @Test
    void columnNotOnTheEntityIsRejected() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        assertThrows(IllegalArgumentException.class,
            () -> streamer.validate(List.of("code", "not_canonical"), List.of("code"), entity("code")));
    }

    @Test
    void aValidProjectionDoesNotThrow() {
        EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();
        streamer.validate(List.of("code", "name"), List.of("code"), entity("code", "name"));
    }
}

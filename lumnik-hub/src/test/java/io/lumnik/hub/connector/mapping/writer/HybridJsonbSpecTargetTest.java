package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1989: a hybrid target is a hub table under {@code ext} or {@code connector} (where an Entity fuses it, #1627).
 * The writer runs its DDL on it as {@code lumnik_admin}, so any other schema would hand an integrator the platform's
 * own tables. The rule sits in the record itself: no caller, parser or not, can build a spec around another schema.
 * Under connector, the {@code v_} names stay the entity views': a hybrid table taking one first would break the Entity
 * that later needs it. A {@code t_} name is a hybrid target's own convention there (#1820).
 */
class HybridJsonbSpecTargetTest {

    @ParameterizedTest
    @ValueSource(strings = {"identity.app_user", "platform.secret", "core.audit_event", "pg_catalog.pg_class",
        "public.items", "items", "rag.chunk", "connector.v_customer"})
    void a_table_outside_ext_and_connector_is_refused_by_every_way_in(String table) {
        assertThatThrownBy(() -> HybridJsonbSpec.fromTargetConfig(Map.of("table", table, "unique_key", "$.id")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ext.<name> or connector.<name>");
        assertThatThrownBy(() -> new HybridJsonbSpec(table, "$.id", List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ext.<name> or connector.<name>");
    }

    /** A table that is not text is refused in the same words, not as a ClassCastException a run cannot phrase. */
    @Test
    void a_table_that_is_not_text_is_refused_as_a_name() {
        assertThatThrownBy(() -> HybridJsonbSpec.fromTargetConfig(Map.of("table", 42, "unique_key", "$.id")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ext.<name> or connector.<name>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ext.items", "connector.items", "connector.t_orders"})
    void ext_and_connector_are_the_hybrid_schemas(String table) {
        assertThat(HybridJsonbSpec.fromTargetConfig(Map.of("table", table, "unique_key", "$.id")).table())
            .isEqualTo(table);
        assertThat(HybridJsonbSpec.targetProblem(table)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ext.it'ems", "ext.", "ext.a.b", "1ext.items"})
    void the_shape_is_still_a_plain_pair(String table) {
        assertThat(HybridJsonbSpec.targetProblem(table)).isPresent();
    }
}

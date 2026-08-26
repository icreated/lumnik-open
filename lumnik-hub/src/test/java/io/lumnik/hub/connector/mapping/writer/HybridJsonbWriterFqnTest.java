package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review round — the identifier guard must fail fast on a blank SEGMENT, not just on a blank
 * whole reference: {@code "schema."} used to quote silently into {@code "schema".""}.
 */
class HybridJsonbWriterFqnTest {

    @Test
    void a_qualified_reference_is_quoted_segment_by_segment() {
        assertThat(HybridJsonbWriter.fqn("connector.t_x")).isEqualTo("\"connector\".\"t_x\"");
    }

    @Test
    void a_trailing_dot_is_refused() {
        assertThatThrownBy(() -> HybridJsonbWriter.fqn("schema."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blank identifier segment");
    }

    @Test
    void a_leading_dot_is_refused() {
        assertThatThrownBy(() -> HybridJsonbWriter.fqn(".table"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blank identifier segment");
    }

    @Test
    void a_blank_reference_is_still_refused() {
        assertThatThrownBy(() -> HybridJsonbWriter.fqn("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("identifier required");
    }
}

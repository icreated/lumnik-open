package io.lumnik.extraction.spi.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProjectionDocumentTest {

    @Test
    void blankIdRejected() {
        assertThatThrownBy(() -> new ProjectionDocument("", Map.of("k", "v"), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyKeyRejected() {
        assertThatThrownBy(() -> new ProjectionDocument("id", Map.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validDocumentAccepted() {
        assertThatCode(() -> new ProjectionDocument("id", Map.of("k", "v"), Map.of("f", "x")))
            .doesNotThrowAnyException();
    }

    @Test
    void projectionDefinitionRejectsBlankEntity() {
        assertThatThrownBy(() -> new ProjectionDefinition("", "simple",
            List.of(new ProjectionField("f", ProjectionFieldMode.EXACT, false))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

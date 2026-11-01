package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionContextTest {

    private static final String MESSAGE = "{doc_key} is still {state}. Owner: {owner} ({laboratory})";

    @Test
    void columns_are_the_cited_non_reserved_names_then_the_declared_ones_once_each() {
        assertThat(DecisionContext.columns(MESSAGE, DormancyMessage.PLACEHOLDERS, List.of("state", "owner_email", "owner")))
            .containsExactly("owner", "laboratory", "state", "owner_email");
        assertThat(DecisionContext.columns(null, DormancyMessage.PLACEHOLDERS, List.of())).isEmpty();
    }

    /** {state} in the sentence is the resolved state; a source column named state rides in fields as written. */
    @Test
    void the_reserved_state_wins_in_the_sentence_and_the_column_rides_in_fields() {
        Map<String, Object> context = new HashMap<>(Map.of("owner", "Dr. Lina Moreau", "laboratory", "Cryobiology Lab",
            "state", "open "));
        String sentence = DecisionContext.render(MESSAGE, context, Map.of("doc_key", "DOOR-17", "state", "OPEN"));
        assertThat(sentence).isEqualTo("DOOR-17 is still OPEN. Owner: Dr. Lina Moreau (Cryobiology Lab)");
        assertThat(DecisionContext.fields(context))
            .containsEntry("state", "open ").containsEntry("owner", "Dr. Lina Moreau");
    }

    @Test
    void null_is_empty_in_the_sentence_and_null_in_fields() {
        Map<String, Object> context = new HashMap<>();
        context.put("owner", null);
        assertThat(DecisionContext.render("Owner: {owner}", context, Map.of())).isEqualTo("Owner: ");
        assertThat(DecisionContext.fields(context)).containsEntry("owner", null);
    }

    @Test
    void temporal_and_numeric_values_render_as_a_process_renders_them() {
        Map<String, Object> context = Map.of("at", Timestamp.from(Instant.parse("2026-10-26T10:00:00Z")),
            "t", new BigDecimal("-67.4000"));
        assertThat(DecisionContext.render("{at} {t}", context, Map.of())).isEqualTo("2026-10-26T10:00:00Z -67.4");
        assertThat(DecisionContext.fields(context))
            .containsEntry("at", "2026-10-26T10:00:00Z").containsEntry("t", new BigDecimal("-67.4000"));
    }
}

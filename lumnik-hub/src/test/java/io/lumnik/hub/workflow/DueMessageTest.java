package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DueMessageTest {

    private static WorkflowGraph graph(String message) {
        return new WorkflowGraph("order-line", "adv", "connector.v_order_line", "stage", "declared", "line",
            null, List.of(new WorkflowGraph.StateDto("CONFIRMED", "confirmée", List.of(), false)), "CONFIRMED",
            List.of(), new WorkflowManifest.DueDef("confirmed_date", List.of("CONFIRMED"), null, message));
    }

    @Test
    void the_default_sentence_is_english_and_says_what_the_data_says() {
        assertThat(DueMessage.render(graph(null), "L-7", "CONFIRMED", "2026-10-14"))
            .isEqualTo("L-7: due 2026-10-14, still in \"CONFIRMED\" — declared due date in order-line");
    }

    @Test
    void a_declared_sentence_names_the_label() {
        assertThat(DueMessage.render(graph("Ligne {doc_key} : livraison confirmée pour le {due}, toujours {state_label}"),
                "L-7", "CONFIRMED", "2026-10-14"))
            .isEqualTo("Ligne L-7 : livraison confirmée pour le 2026-10-14, toujours confirmée");
    }

    /** The day the clocks go back in Paris lasts 25 hours: its end is 23:00 UTC, not 22:00. */
    @Test
    void a_date_ends_at_the_start_of_the_next_day_in_its_zone() {
        assertThat(WorkflowDueSweep.endOfDay(LocalDate.parse("2026-10-24"), ZoneId.of("Europe/Paris")))
            .isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
        assertThat(WorkflowDueSweep.endOfDay(LocalDate.parse("2026-10-25"), ZoneId.of("Europe/Paris")))
            .isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
        assertThat(WorkflowDueSweep.endOfDay(LocalDate.parse("2026-10-25"), ZoneOffset.UTC))
            .isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
    }
}

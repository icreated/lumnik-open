package io.lumnik.extraction.spi.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AskResultDisputedTest {

    static final Disputed P1008 = new Disputed("P-1008", "city", "Ville", "Lyon", "Lyon", true,
        List.of(new Disputed.SourceValue("t_erp", "Lyon"), new Disputed.SourceValue("t_crm", "Villeurbanne")));

    @Test
    void the_existing_constructors_carry_an_empty_list_and_no_truncation() {
        AskResult r = new AskResult("a", "SELECT 1", List.of(), 0, null, List.of("lifecycle"));
        assertThat(r.disputed()).isEmpty();
        assertThat(r.disputedTruncated()).isFalse();
        assertThat(new AskResult("a", "", List.of(), 0).disputed()).isEmpty();
    }

    @Test
    void a_null_list_is_an_empty_list_and_the_list_is_a_copy() {
        AskResult r = new AskResult("a", "", List.of(), 0, null, null, null, false);
        assertThat(r.disputed()).isNotNull().isEmpty();
    }

    @Test
    void withDisputed_keeps_everything_else_and_sets_both_fields() {
        AskResult base = new AskResult("a", "SELECT 1", List.of(), 2, "2026-09-13T00:00:00Z", List.of("lifecycle"));
        AskResult r = base.withDisputed(List.of(P1008), true);
        assertThat(r.answer()).isEqualTo("a");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.otherDoors()).containsExactly("lifecycle", AskResult.DOOR_CONFLICTS);
        assertThat(r.disputed()).containsExactly(P1008);
        assertThat(r.disputedTruncated()).isTrue();
    }

    @Test
    void withDisputed_names_the_conflicts_door_once_and_only_when_something_is_disputed() {
        AskResult base = new AskResult("a", "", List.of(), 0, null, List.of("lifecycle"));
        assertThat(base.withDisputed(List.of(), false).otherDoors()).containsExactly("lifecycle");
        AskResult withDoor = new AskResult("a", "", List.of(), 0, null, List.of(AskResult.DOOR_CONFLICTS));
        assertThat(withDoor.withDisputed(List.of(P1008), false).otherDoors()).containsExactly(AskResult.DOOR_CONFLICTS);
    }

    @Test
    void an_empty_list_is_never_truncated_on_any_construction_path() {
        AskResult base = new AskResult("a", "", List.of(), 0, null, List.of());
        assertThat(base.withDisputed(List.of(), true).disputedTruncated()).isFalse();
        assertThat(new AskResult("a", "", List.of(), 0, null, List.of(), null, true).disputedTruncated()).isFalse();
        assertThat(base.withDisputed(List.of(P1008), true).disputedTruncated()).isTrue();
    }

    @Test
    void withDisputed_reads_a_null_list_as_empty_like_the_constructor() {
        AskResult r = new AskResult("a", "", List.of(), 0, null, List.of("lifecycle")).withDisputed(null, false);
        assertThat(r.disputed()).isEmpty();
        assertThat(r.otherDoors()).containsExactly("lifecycle");
    }

    @Test
    void a_null_source_value_survives_as_null() {
        Disputed d = new Disputed("K", "c", "c", "x", "x", true, List.of(new Disputed.SourceValue("s", null)));
        assertThat(d.sources().get(0).value()).isNull();
    }
}

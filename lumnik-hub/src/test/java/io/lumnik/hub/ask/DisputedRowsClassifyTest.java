package io.lumnik.hub.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lumnik.extraction.spi.ask.Disputed;
import io.lumnik.hub.entity.EntityManifest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisputedRowsClassifyTest {

    static final DisputedRows.Filter CITY_LYON = new DisputedRows.Filter("city", List.of("Lyon"));
    static final DisputedRows.Filter CITY_VILLEURBANNE = new DisputedRows.Filter("city", List.of("Villeurbanne"));
    static final DisputedRows.Filter SEGMENT_GOLD = new DisputedRows.Filter("segment", List.of("GOLD"));
    static final String LEDGER = "[{\"src\": \"t_erp\", \"value\": \"Lyon\"}, {\"src\": \"t_crm\", \"value\": \"Villeurbanne\"}]";

    @Test
    void retained_value_in_the_filter_is_counted() {
        Disputed d = DisputedRows.classify(new DisputedRows.ProbeRow("P-1008", "Lyon", LEDGER), CITY_LYON, "Ville").orElseThrow();
        assertThat(d).isEqualTo(new Disputed("P-1008", "city", "Ville", "Lyon", "Lyon", true, List.of(
            new Disputed.SourceValue("t_erp", "Lyon"), new Disputed.SourceValue("t_crm", "Villeurbanne"))));
    }

    @Test
    void a_source_holding_the_filtered_value_while_the_record_kept_another_is_excluded() {
        Disputed d = DisputedRows.classify(new DisputedRows.ProbeRow("P-1008", "Lyon", LEDGER), CITY_VILLEURBANNE, "Ville").orElseThrow();
        assertThat(d.counted()).isFalse();
        assertThat(d.matched()).isEqualTo("Villeurbanne");
        assertThat(d.retained()).isEqualTo("Lyon");
    }

    @Test
    void neither_is_nothing_and_a_null_ledger_value_survives() {
        assertThat(DisputedRows.classify(new DisputedRows.ProbeRow("K", "Paris", LEDGER),
            new DisputedRows.Filter("city", List.of("Nice")), "Ville")).isEmpty();
        Disputed d = DisputedRows.classify(new DisputedRows.ProbeRow("K", "Lyon",
            "[{\"src\": \"a\", \"value\": \"Lyon\"}, {\"src\": \"b\", \"value\": null}]"), CITY_LYON, "Ville").orElseThrow();
        assertThat(d.sources().get(1).value()).isNull();
    }

    @Test
    void the_probe_binds_every_value_and_names_only_card_identifiers() {
        var plan = new DisputedRows.Plan("connector.v_dspcustomer", "dspcustomer",
            List.of(CITY_LYON, SEGMENT_GOLD), DisputedRows.Counted.IF_ANY_ROW);
        DisputedRows.Probe p = DisputedRows.probe(plan, CITY_LYON, List.of("code"), true);
        assertThat(p.sql())
            .contains("FROM \"connector\".\"v_dspcustomer\"")
            .contains("\"segment\"::text = ANY(?)")
            .contains("jsonb_typeof(\"_conflicts\" -> ?) = 'array'")
            .contains("\"city\"::text = ANY(?)")
            .contains("jsonb_array_elements(\"_conflicts\" -> ?)")
            .contains("LIMIT 11")
            .doesNotContain("Lyon").doesNotContain("GOLD");
        // order: the ledger key in the SELECT, the other filters, then the three for the filtered column
        assertThat(p.params()).containsExactly("city", List.of("GOLD"), "city", List.of("Lyon"), "city", List.of("Lyon"),
            List.of("Lyon"));
        assertThat(p.sql().chars().filter(ch -> ch == '?').count()).isEqualTo(p.params().size());
    }

    @Test
    void the_probe_orders_excluded_records_before_the_key() {
        var plan = new DisputedRows.Plan("connector.v_dspcustomer", "dspcustomer", List.of(CITY_LYON), DisputedRows.Counted.IF_ANY_ROW);
        String sql = DisputedRows.probe(plan, CITY_LYON, List.of("code"), true).sql();
        assertThat(sql).contains("ORDER BY (\"city\"::text = ANY(?)) IS TRUE, \"code\"");
    }

    @Test
    void without_admissible_counted_entries_the_probe_reads_excluded_candidates_only() {
        var plan = new DisputedRows.Plan("connector.v_dspcustomer", "dspcustomer",
            List.of(CITY_LYON, SEGMENT_GOLD), DisputedRows.Counted.NEVER);
        DisputedRows.Probe p = DisputedRows.probe(plan, CITY_LYON, List.of("code"), false);
        assertThat(p.sql())
            .contains("AND (\"city\"::text = ANY(?)) IS NOT TRUE AND EXISTS (")
            .doesNotContain(" OR EXISTS");
        assertThat(p.params()).containsExactly("city", List.of("GOLD"), "city", List.of("Lyon"), "city", List.of("Lyon"),
            List.of("Lyon"));
        assertThat(p.sql().chars().filter(ch -> ch == '?').count()).isEqualTo(p.params().size());
    }

    @Test
    void a_filter_on_the_match_key_is_never_probed_but_still_narrows_the_others() {
        var code = new DisputedRows.Filter("code", List.of("P-1008"));
        var plan = new DisputedRows.Plan("connector.v_dspcustomer", "dspcustomer",
            List.of(code, CITY_LYON), DisputedRows.Counted.IF_ANY_ROW);
        assertThat(DisputedRows.probed(plan, List.of("code"))).containsExactly(CITY_LYON);
        assertThat(DisputedRows.probed(plan, List.of("CODE"))).containsExactly(CITY_LYON);
        assertThat(DisputedRows.probe(plan, CITY_LYON, List.of("code"), true).sql()).contains("\"code\"::text = ANY(?)");
    }

    @Test
    void a_quote_in_a_value_is_a_parameter_not_sql() {
        var f = new DisputedRows.Filter("city", List.of("L'Isle"));
        var plan = new DisputedRows.Plan("connector.v_dspcustomer", "dspcustomer", List.of(f), DisputedRows.Counted.IF_ANY_ROW);
        assertThat(DisputedRows.probe(plan, f, List.of("code"), true).sql()).doesNotContain("Isle");
    }

    @Test
    void a_resolution_entity_has_no_key_columns() {
        EntityManifest matched = new EntityManifest("Entity", "C", List.of("code"), List.of());
        assertThat(DisputedRows.keyColumns(matched)).contains(List.of("code"));
        EntityManifest none = new EntityManifest("Entity", "C", null, List.of());
        assertThat(DisputedRows.keyColumns(none)).isEmpty();
    }

    @Test
    void excluded_come_first_the_cap_is_ten_and_truncation_is_said() {
        List<Disputed> counted = new ArrayList<>();
        List<Disputed> excluded = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            counted.add(new Disputed("C" + i, "city", "Ville", "Lyon", "Lyon", true, List.of()));
            excluded.add(new Disputed("E" + i, "city", "Ville", "Lyon", "Nice", false, List.of()));
        }
        List<Disputed> mixed = new ArrayList<>(counted);
        mixed.addAll(excluded);
        DisputedRows.Outcome o = DisputedRows.assemble(mixed, false, true);
        assertThat(o.disputed()).hasSize(10);
        assertThat(o.disputed().subList(0, 8)).allMatch(d -> !d.counted());
        assertThat(o.truncated()).isTrue();
    }

    @Test
    void counted_entries_are_dropped_when_membership_is_not_demonstrable_and_overflow_is_truncation() {
        Disputed c = new Disputed("C", "city", "Ville", "Lyon", "Lyon", true, List.of());
        Disputed e = new Disputed("E", "city", "Ville", "Lyon", "Nice", false, List.of());
        DisputedRows.Outcome o = DisputedRows.assemble(List.of(c, e), true, false);
        assertThat(o.disputed()).containsExactly(e);
        assertThat(o.truncated()).isTrue();
    }

    @Test
    void an_empty_list_is_never_truncated_so_no_client_sees_a_flag_without_a_block() {
        Disputed c = new Disputed("C", "city", "Ville", "Lyon", "Lyon", true, List.of());
        DisputedRows.Outcome o = DisputedRows.assemble(List.of(c), true, false);
        assertThat(o.disputed()).isEmpty();
        assertThat(o.truncated()).isFalse();
    }

    @Test
    void a_ledger_that_is_not_an_array_is_nothing() {
        assertThat(DisputedRows.classify(new DisputedRows.ProbeRow("K", "Lyon", "{\"x\":1}"), CITY_LYON, "Ville")).isEmpty();
        assertThat(DisputedRows.classify(new DisputedRows.ProbeRow("K", "Lyon", "null"), CITY_LYON, "Ville")).isEmpty();
    }

    @Test
    void a_probe_without_key_columns_is_refused() {
        var plan = new DisputedRows.Plan("connector.v_x", "x", List.of(CITY_LYON), DisputedRows.Counted.IF_ANY_ROW);
        assertThrows(IllegalArgumentException.class,
            () -> DisputedRows.probe(plan, CITY_LYON, List.of(), true));
    }

    /** A date filter matches the instant a source holds, whatever its text (#1326 review). */
    @Test
    void a_date_filter_matches_a_source_holding_that_instant() {
        DisputedRows.Filter due = new DisputedRows.Filter("due", List.of("2026-07-05"), true);
        String ledger = "[{\"src\": \"t_erp\", \"value\": \"2026-07-05 08:00:00+00\"},"
            + " {\"src\": \"t_csv\", \"value\": \"2026-07-05 00:00:00+00\"}]";
        Disputed d = DisputedRows.classify(new DisputedRows.ProbeRow("SO-2", "2026-07-05 08:00:00+00", ledger), due, "Due")
            .orElseThrow();
        assertThat(d.counted()).isFalse();
        assertThat(d.matched()).isEqualTo("2026-07-05 00:00:00+00");
        assertThat(d.retained()).isEqualTo("2026-07-05 08:00:00+00");
    }
}

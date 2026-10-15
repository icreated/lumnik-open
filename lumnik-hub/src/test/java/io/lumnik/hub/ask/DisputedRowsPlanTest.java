package io.lumnik.hub.ask;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.extraction.spi.ask.SchemaCard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisputedRowsPlanTest {

    static final SchemaCard CARD = new SchemaCard("dsp", List.of(
        new SchemaCard.Table("connector.v_dspcustomer", "Customer", List.of(
            new SchemaCard.Column("code", "text", "Code client"),
            new SchemaCard.Column("city", "text", "Ville"),
            new SchemaCard.Column("segment", "text", "Segment"))),
        new SchemaCard.Table("connector.t_dsp_erp", "ERP", List.of(
            new SchemaCard.Column("city", "text", "city")))));

    static final String V = "connector.v_dspcustomer";

    private static Optional<DisputedRows.Plan> plan(String sql) {
        return DisputedRows.plan(sql, sql + "\nLIMIT 500", CARD);
    }

    @Test
    void two_and_ed_clean_filters_on_a_fused_view_make_a_plan() {
        var p = plan("SELECT COUNT(*) FROM " + V + " WHERE city = 'Lyon' AND segment = 'GOLD'").orElseThrow();
        assertThat(p.view()).isEqualTo(V);
        assertThat(p.entity()).isEqualTo("dspcustomer");
        assertThat(p.filters()).containsExactly(
            new DisputedRows.Filter("city", List.of("Lyon")),
            new DisputedRows.Filter("segment", List.of("GOLD")));
        assertThat(p.countedReportable(1, 500)).isTrue();
    }

    @Test
    void an_in_list_and_quoted_names_are_clean() {
        var p = plan("SELECT \"code\" FROM \"connector\".\"v_dspcustomer\" WHERE \"city\" IN ('Lyon', 'Villeurbanne')")
            .orElseThrow();
        assertThat(p.filters()).containsExactly(new DisputedRows.Filter("city", List.of("Lyon", "Villeurbanne")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = 'Lyon' OR segment = 'GOLD'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE NOT city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city <> 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city != 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city NOT IN ('Lyon')",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city > 'L'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city LIKE 'Ly%'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE lower(city) = 'lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city::text = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer c WHERE w.city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer AS v WHERE w.\"city\" = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE other.city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer AS v WHERE a.v.city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer JOIN connector.t_dsp_erp ON true WHERE city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = (SELECT city FROM connector.t_dsp_erp LIMIT 1)",
        "SELECT code FROM connector.v_dspcustomer WHERE city = 'Lyon' UNION SELECT city FROM connector.t_dsp_erp",
        "SELECT COUNT(*) FROM connector.t_dsp_erp WHERE city = 'Lyon'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE (city = 'Lyon')",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE phone = '01'",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = 1",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city IN ('Lyon',)",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city IN ('Lyon' 'Nice')",
        "SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city IN (1)"
    })
    void anything_else_makes_no_plan(String sql) {
        assertThat(plan(sql)).isEmpty();
    }

    @Test
    void an_alias_with_as_qualifies_the_filter_columns() {
        var p = plan("SELECT v.\"name\" FROM connector.v_dspcustomer AS v WHERE v.\"city\" = 'Villeurbanne'").orElseThrow();
        assertThat(p.filters()).containsExactly(new DisputedRows.Filter("city", List.of("Villeurbanne")));
    }

    @Test
    void a_bare_alias_and_mixed_qualification_are_read() {
        var p = plan("SELECT code FROM connector.v_dspcustomer v WHERE V.city = 'Lyon' AND segment = 'GOLD'").orElseThrow();
        assertThat(p.filters()).containsExactly(
            new DisputedRows.Filter("city", List.of("Lyon")),
            new DisputedRows.Filter("segment", List.of("GOLD")));
    }

    @Test
    void the_view_name_qualifies_when_there_is_no_alias() {
        assertThat(plan("SELECT code FROM connector.v_dspcustomer WHERE v_dspcustomer.city = 'Lyon'")).isPresent();
    }

    @Test
    void an_alias_with_unqualified_columns_makes_a_plan() {
        assertThat(plan("SELECT code FROM connector.v_dspcustomer AS v WHERE city = 'Lyon'")).isPresent();
    }

    @Test
    void a_model_written_limit_or_offset_forbids_counted_entries() {
        String sql = "SELECT code FROM " + V + " WHERE city = 'Lyon' ORDER BY code LIMIT 1";
        var p = DisputedRows.plan(sql, sql, CARD).orElseThrow();
        assertThat(p.countedReportable(1, 500)).isFalse();
        String off = "SELECT code FROM " + V + " WHERE city = 'Lyon' OFFSET 2";
        assertThat(DisputedRows.plan(off, off + "\nLIMIT 500", CARD).orElseThrow().countedReportable(1, 500)).isFalse();
    }

    @Test
    void having_forbids_counted_entries_grouped_or_not() {
        String a = "SELECT COUNT(*) FROM " + V + " WHERE city = 'Lyon' HAVING COUNT(*) > 100";
        String b = "SELECT segment, COUNT(*) FROM " + V + " WHERE city = 'Lyon' GROUP BY segment HAVING COUNT(*) > 1";
        assertThat(plan(a).orElseThrow().countedReportable(1, 500)).isFalse();
        assertThat(plan(b).orElseThrow().countedReportable(1, 500)).isFalse();
    }

    @Test
    void a_result_cut_at_the_cap_forbids_counted_entries_unless_aggregate_only() {
        String rows = "SELECT code FROM " + V + " WHERE city = 'Lyon'";
        assertThat(DisputedRows.plan(rows, rows + "\nLIMIT 500", CARD).orElseThrow().countedReportable(500, 500)).isFalse();
        String agg = "SELECT COUNT(*), SUM(code::numeric) AS s FROM " + V + " WHERE city = 'Lyon' LIMIT 3";
        assertThat(DisputedRows.plan(agg, agg, CARD).orElseThrow().countedReportable(1, 500)).isTrue();
    }

    @Test
    void a_grouped_aggregate_without_bound_or_having_may_report_counted() {
        assertThat(plan("SELECT segment, COUNT(*) FROM " + V + " WHERE city = 'Lyon' GROUP BY segment")
            .orElseThrow().countedReportable(1, 500)).isTrue();
    }

    @Test
    void fetch_first_is_a_model_written_bound() {
        var p = plan("SELECT code FROM " + V + " WHERE city = 'Lyon' ORDER BY code FETCH FIRST 1 ROWS ONLY").orElseThrow();
        assertThat(p.countedReportable(1, 500)).isFalse();
    }

    @Test
    void an_aggregate_that_returned_no_row_counted_nothing() {
        String off = "SELECT COUNT(*) FROM " + V + " WHERE city = 'Lyon' OFFSET 1";
        assertThat(DisputedRows.plan(off, off, CARD).orElseThrow().countedReportable(1, 500)).isFalse();
        String zero = "SELECT COUNT(*) FROM " + V + " WHERE city = 'Lyon' LIMIT 0";
        assertThat(DisputedRows.plan(zero, zero, CARD).orElseThrow().countedReportable(0, 500)).isFalse();
    }

    @Test
    void distinct_still_makes_a_plan() {
        assertThat(plan("SELECT DISTINCT code FROM " + V + " WHERE city = 'Lyon'")).isPresent();
    }
}

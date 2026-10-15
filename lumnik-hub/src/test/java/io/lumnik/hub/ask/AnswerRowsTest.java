package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the phrasing model reads of a result: a prefix that fits a budget of SERIALISED characters (#1067). */
class AnswerRowsTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Map<String, Object>> catalogue(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row("reference", "2195.300." + String.format("%03d", i),
                "description", "Pompe Resort " + i + " ".repeat(1) + "x".repeat(120)));
        }
        return rows;
    }

    @Test void a_result_that_fits_is_read_whole() {
        var rows = catalogue(5);
        var w = AnswerRows.fit(rows, 6000);
        assertThat(w.shown()).isEqualTo(rows);
        assertThat(w.total()).isEqualTo(5);
        assertThat(w.truncated()).isFalse();
    }

    @Test void a_wide_catalogue_is_cut_to_a_prefix_whose_serialised_size_fits() {
        var rows = catalogue(500);
        var w = AnswerRows.fit(rows, 6000);

        assertThat(w.truncated()).isTrue();
        assertThat(w.total()).isEqualTo(500);
        assertThat(w.shown()).isEqualTo(rows.subList(0, w.shown().size()));
        assertThat(AnswerRows.serialisedSize(w.shown())).isLessThanOrEqualTo(6000);
        // maximal: one row more would not have fitted
        assertThat(AnswerRows.serialisedSize(rows.subList(0, w.shown().size() + 1))).isGreaterThan(6000);
        assertThat(w.shown().size()).isBetween(20, 80); // about fifty catalogue rows
    }

    @Test void the_budget_counts_the_json_actually_sent_not_the_raw_cells() {
        // '<' travels as the six characters <: 100 of them are 600 characters of prompt, not 100
        var rows = List.of(row("note", "<".repeat(100)), row("note", "<".repeat(100)));
        assertThat(AnswerRows.serialisedSize(rows)).isGreaterThan(1000); // 200 raw characters, over 1200 sent
        assertThat(AnswerRows.fit(rows, 1000).shown()).hasSize(1);
    }

    @Test void at_least_one_row_is_read_however_wide() {
        var rows = List.of(row("note", "y".repeat(50_000)), row("note", "z"));
        var w = AnswerRows.fit(rows, 100);
        assertThat(w.shown()).hasSize(1);
        assertThat(w.total()).isEqualTo(2);
        assertThat(w.truncated()).isTrue();
    }

    @Test void a_result_that_fits_keeps_every_cell_as_it_is_however_long() {
        var rows = List.of(row("note", "y".repeat(AnswerRows.CELL_CAP + 100)));
        var w = AnswerRows.fit(rows, 6000);
        assertThat(w.shown()).isEqualTo(rows); // a lookup of one long description is read in full
    }

    @Test void an_over_budget_result_is_read_with_its_long_cells_cut_and_numbers_untouched() {
        var rows = new ArrayList<>(catalogue(400));
        rows.set(0, row("reference", "R0", "description", "y".repeat(5000), "n", 42, "none", null));
        var w = AnswerRows.fit(rows, 6000);
        var first = w.shown().get(0);
        assertThat((String) first.get("description")).isEqualTo("y".repeat(AnswerRows.CELL_CAP) + "…");
        assertThat(first.get("n")).isEqualTo(42);
        assertThat(first.get("none")).isNull();
        assertThat((String) rows.get(0).get("description")).as("the result itself is never cut").hasSize(5000);
    }

    @Test void a_single_over_budget_row_is_read_with_its_cell_cut() {
        var w = AnswerRows.fit(List.of(row("note", "y".repeat(50_000))), 6000);
        assertThat(w.shown()).hasSize(1);
        assertThat(((String) w.shown().get(0).get("note"))).hasSize(AnswerRows.CELL_CAP + 1);
        assertThat(w.truncated()).isFalse();
    }

    /** A 45-column record of 300-character cells is 13k+ characters even cut per cell: the budget still holds (#1067 review). */
    @Test void a_wide_single_row_is_read_within_the_budget() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 45; i++) wide.put("column_" + i, "z".repeat(1000));
        var w = AnswerRows.fit(List.of(wide), 6000);
        assertThat(w.shown()).hasSize(1);
        assertThat(AnswerRows.serialisedSize(w.shown())).isLessThanOrEqualTo(6000);
        assertThat(w.shown().get(0)).as("every column is still there, each a shorter text").hasSize(45);
    }

    @Test void a_row_of_thousands_of_columns_keeps_the_first_ones_within_the_budget() {
        Map<String, Object> huge = new LinkedHashMap<>();
        for (int i = 0; i < 3000; i++) huge.put("c" + i, "v" + i);
        var w = AnswerRows.fit(List.of(huge), 600);
        assertThat(AnswerRows.serialisedSize(w.shown())).isLessThanOrEqualTo(600);
        assertThat(w.shown().get(0)).containsKey("c0");
    }

    @Test void a_single_aggregate_row_is_read_as_it_is() {
        var rows = List.of(row("count", 12345L));
        var w = AnswerRows.fit(rows, 6000);
        assertThat(w.shown()).isEqualTo(rows);
        assertThat(w.truncated()).isFalse();
    }

    @Test void the_empty_result_is_not_a_window() {
        var w = AnswerRows.fit(List.of(), 6000);
        assertThat(w.shown()).isEmpty();
        assertThat(w.truncated()).isFalse();
    }
}

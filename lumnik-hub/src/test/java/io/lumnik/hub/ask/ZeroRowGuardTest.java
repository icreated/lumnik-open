package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #1982: a count of 0 is a zero-row result told another way — the aggregates found nothing to read. */
class ZeroRowGuardTest {

    private static List<Map<String, Object>> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) row.put("c" + i, values[i]);
        return List.of(row);
    }

    private static boolean nothing(String sql, Object... values) {
        return ZeroRowGuard.foundNothing(sql, row(values));
    }

    @Test
    void aCountOfZeroFoundNothing() {
        assertThat(nothing("SELECT COUNT(*) FROM connector.v_customer WHERE city = 'Clermont-Ferrand' LIMIT 500", 0L)).isTrue();
        assertThat(nothing("SELECT count(DISTINCT city) AS n FROM connector.v_customer", 0L)).isTrue();
        assertThat(nothing("SELECT COUNT(*) total FROM connector.v_customer", 0)).isTrue();
    }

    @Test
    void aCountWhoseCentreMatchedNoRowFoundNothing() {
        assertThat(nothing("SELECT COUNT(*) FROM \"connector\".\"v_customer_a\" WHERE connector.distance_m(\"latitude\", "
            + "\"longitude\", (SELECT \"latitude\" FROM \"connector\".\"v_depot_a\" WHERE \"depot\" = 'Clermont-Ferrand'), "
            + "(SELECT \"longitude\" FROM \"connector\".\"v_depot_a\" WHERE \"depot\" = 'Clermont-Ferrand')) <= 50000 LIMIT 500",
            0L)).isTrue();
        assertThat(nothing("SELECT COUNT(*) FROM \"connector\".\"v_customer_a\" c JOIN \"connector\".\"v_depot_a\" d "
            + "ON d.city = 'Lyon' WHERE connector.distance_m(c.latitude, c.longitude, d.latitude, d.longitude) <= 20000",
            0L)).isTrue();
    }

    @Test
    void anAggregateOverNoRowIsNullAndFoundNothing() {
        assertThat(nothing("SELECT SUM(amount) FROM connector.v_order WHERE supplier = 'SUP-009'", (Object) null)).isTrue();
        assertThat(nothing("SELECT COUNT(*), SUM(amount) AS total FROM connector.v_order WHERE supplier = 'SUP-009'",
            0L, null)).isTrue();
        assertThat(nothing("SELECT max(ordered_on) FROM connector.v_order WHERE supplier = 'SUP-009'", (Object) null)).isTrue();
    }

    /** The outer query still returns its one row: grouping inside a sub-select does not change that. */
    @Test
    void aGroupingInsideASubSelectIsRead() {
        assertThat(nothing("SELECT COUNT(*) FROM connector.v_order WHERE customer IN "
            + "(SELECT customer FROM connector.v_order GROUP BY customer HAVING COUNT(*) > 3)", 0L)).isTrue();
        assertThat(nothing("SELECT COUNT(*) FROM (SELECT city FROM connector.v_customer UNION "
            + "SELECT city FROM connector.v_depot) s WHERE city = 'Nope'", 0L)).isTrue();
    }

    @Test
    void aFromInsideAnAggregateDoesNotEndTheSelectList() {
        assertThat(nothing("SELECT max(extract(year FROM ordered_on)) FROM connector.v_order WHERE supplier = 'SUP-009'",
            (Object) null)).isTrue();
    }

    @Test
    void aCountThatFoundSomethingIsAFigure() {
        assertThat(nothing("SELECT COUNT(*) FROM connector.v_customer", 5L)).isFalse();
        assertThat(nothing("SELECT COUNT(*), SUM(amount) FROM connector.v_order", 3L, null))
            .as("three rows whose amounts are all empty: the count is a figure").isFalse();
    }

    @Test
    void aTotalOrAMinimumOfZeroIsAFigure() {
        assertThat(nothing("SELECT SUM(amount) FROM connector.v_order", BigDecimal.ZERO)).isFalse();
        assertThat(nothing("SELECT MIN(stock) FROM connector.v_product", 0)).isFalse();
        assertThat(nothing("SELECT avg(stock) FROM connector.v_product", new BigDecimal("0.000"))).isFalse();
    }

    @Test
    void aZeroThatIsNotAnUngroupedAggregateIsAFigure() {
        assertThat(nothing("SELECT stock FROM connector.v_product WHERE code = 'P-1'", 0)).as("a lookup").isFalse();
        assertThat(nothing("SELECT COUNT(*) FROM connector.v_customer GROUP BY city", 0L)).as("grouped").isFalse();
        assertThat(nothing("SELECT COUNT(*) + 1 FROM connector.v_customer", 0L)).as("arithmetic around it").isFalse();
        assertThat(nothing("SELECT (SELECT COUNT(*) FROM connector.v_customer)", 0L)).as("a scalar sub-select").isFalse();
    }

    @Test
    void zeroRowsOrSeveralRowsAreNotThisCase() {
        assertThat(ZeroRowGuard.foundNothing("SELECT COUNT(*) FROM connector.v_customer", List.of())).isFalse();
        Map<String, Object> zero = row(0L).get(0);
        assertThat(ZeroRowGuard.foundNothing("SELECT COUNT(*) FROM connector.v_customer", List.of(zero, zero))).isFalse();
        assertThat(ZeroRowGuard.foundNothing(null, row(0L))).isFalse();
    }

    /** Fail-open, by choice: a wrapped aggregate keeps the old answer. */
    @Test
    void notRead() {
        assertThat(nothing("SELECT COALESCE(SUM(amount), 0) FROM connector.v_order WHERE supplier = 'SUP-009'", 0)).isFalse();
        assertThat(nothing("SELECT COUNT(*)::int FROM connector.v_customer", 0)).isFalse();
        assertThat(nothing("SELECT COUNT(*) FILTER (WHERE status = 'LATE') FROM connector.v_order", 0L)).isFalse();
        assertThat(nothing("WITH max(v) AS (SELECT count(*) FROM connector.v_order) SELECT v FROM max", 0L))
            .as("a CTE header is not a select list").isFalse();
        assertThat(nothing("SELECT COUNT(*) FROM connector.v_order WHERE supplier = 'A' "
            + "EXCEPT SELECT COUNT(*) FROM connector.v_order WHERE supplier = 'B'", 0L))
            .as("a set operation: its one row is not one aggregate's").isFalse();
        assertThat(nothing("SELECT COUNT(*), COUNT(DISTINCT city) FROM connector.v_customer", 0L))
            .as("one label, `count`, for both: the row holds one value").isFalse();
    }

}

package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry case: {@code buildSql} emitted no LIMIT, so Postgres planned and executed the full
 * scan-and-sort before the JDBC-side {@code setMaxRows} ever got to discard rows — ORDER BY is a
 * blocking operator, so nothing short of a real {@code LIMIT} bounds the work Postgres does. This
 * test drives {@code buildSql} directly (package-visible, pure) rather than through a live
 * connection.
 */
class ViewDataReaderBuildSqlTest {

    private static ResolvedView demoView() {
        return new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("customer_code", "Code", "text"),
                new ResolvedView.Column("name", "Name", "text")));
    }

    @Test void noFilters_sqlEndsWithAParameterisedLimitBoundToOneBeyondTheCap() {
        String sql = ViewDataReader.buildSql(demoView(), List.of());

        assertTrue(sql.trim().endsWith("LIMIT ?"),
            () -> "expected a parameterised LIMIT clause, got: " + sql);
        // never concatenated: the cap value must not appear as a literal in the SQL text
        assertFalse(sql.contains("1001"), () -> "the cap must be bound, not concatenated: " + sql);
    }

    @Test void twoFilters_theLimitPlaceholderComesAfterBothFilterPlaceholders() {
        ViewFilter f1 = new ViewFilter("name", "~", "ali");
        ViewFilter f2 = new ViewFilter("customer_code", "=", "C-2");
        String sql = ViewDataReader.buildSql(demoView(), List.of(f1, f2));

        // three "?" total: one per filter, plus the LIMIT — and in exactly that order, so that
        // ps.setString(1..2, filterValue) followed by ps.setInt(3, MAX_ROWS + 1) binds correctly.
        assertEquals(3, sql.chars().filter(c -> c == '?').count(),
            () -> "expected filter placeholder x2 + LIMIT placeholder x1, got: " + sql);
        int whereAt = sql.indexOf(" WHERE ");
        int limitAt = sql.indexOf("LIMIT ?");
        assertTrue(whereAt >= 0 && whereAt < limitAt,
            () -> "LIMIT must trail the WHERE filters, got: " + sql);
        // the LIMIT's "?" is the LAST placeholder in the string — binding it after the filter
        // loop (index filters.size() + 1) lines up with this textual order.
        assertEquals(sql.lastIndexOf('?'), sql.indexOf("LIMIT ?") + "LIMIT ".length(),
            () -> "the LIMIT placeholder must be the final '?' in the SQL: " + sql);
    }

    // ── the ::text casts ──────────────────────────────────────────────────────────────────────
    // A cast on the LEFT-hand side makes any btree index on that column unusable, so even an
    // index the manifest author explicitly declared was being bypassed on every view render.

    @Test void equality_onATextColumn_doesNotCastTheColumn() {
        String sql = ViewDataReader.buildSql(demoView(),
                List.of(new ViewFilter("customer_code", "=", "C-2")));

        assertTrue(sql.contains("\"customer_code\" = ?"),
            () -> "a text column must be compared without a cast so its index stays usable: " + sql);
        assertFalse(sql.contains("\"customer_code\"::text = ?"),
            () -> "the left-hand cast defeats the index: " + sql);
    }

    /**
     * #1338: a number against a number column is equal by value — {@code amount=12.5} finds a {@code 12.5000} that the
     * text comparison never matched. The 22P02 this test once guarded against cannot arise: the numeric form is chosen
     * only for an operand that parsed as a number, and the column is already one, so the native cast cannot fail.
     */
    @Test void equality_onANumberColumn_withANumber_comparesByValue_natively() {
        ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("amount", "Amount", "numeric")));

        String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("amount", "=", "12.5")));

        assertTrue(sql.contains("\"amount\"::numeric = ?::numeric") && !sql.contains("CASE WHEN"),
            () -> "a number operand compares by value, natively — no per-row text and regex: " + sql);
    }

    /** One rule for every reader's {@code =} (ViewFilter.isNumeric): a scientific or overlong value stays text. */
    @Test void equality_onANumberColumn_withAValueOutsideTheRule_keepsTheTextComparison() {
        ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("amount", "Amount", "numeric")));
        for (String value : List.of("1e3", "1".repeat(39))) {
            String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("amount", "=", value)));
            assertTrue(sql.contains("\"amount\"::text = ?") && !sql.contains("::numeric = "), () -> value + ": " + sql);
        }
    }

    @Test void equality_onAFloatColumn_withANumber_comparesNatively() {
        ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("ratio", "Ratio", "double precision")));
        String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("ratio", "=", "0.5")));
        assertTrue(sql.contains("\"ratio\" = ?::float8"), () -> sql);
    }

    /** A value that is not a number keeps the exact-text comparison: it matches nothing, and raises nothing. */
    @Test void equality_onANumberColumn_withANonNumber_keepsTheTextComparison() {
        ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("amount", "Amount", "numeric")));

        String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("amount", "=", "lots")));

        assertTrue(sql.contains("\"amount\"::text = ?"), () -> "never ?::numeric on a value that is not one: " + sql);
    }

    @Test void containsOperator_keepsTheCast_itNeedsTextSemantics() {
        ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("amount", "Amount", "numeric")));

        String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("amount", "~", "12")));

        // ILIKE genuinely needs text on a non-text column, and a %value% pattern cannot use a
        // plain btree anyway — there is nothing to win here and correctness to lose.
        assertTrue(sql.contains("\"amount\"::text ILIKE ?"),
            () -> "the contains operator must keep text semantics: " + sql);
    }

    @Test void unknownColumnType_keepsTheCast_failSafe() {
        ResolvedView unknown = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("mystery", "Mystery", null)));

        String sql = ViewDataReader.buildSql(unknown, List.of(new ViewFilter("mystery", "=", "x")));

        // The narrowing is opt-in: without positive knowledge that the column is textual, today's
        // behaviour is preserved exactly.
        assertTrue(sql.contains("\"mystery\"::text = ?"),
            () -> "an unknown type must fall back to the existing shape: " + sql);
    }

    @Test void orderingOnAFloat_comparesNatively_noTextRoundTrip() {
        for (String type : List.of("double precision", "real", "float8", "float4")) {
            ResolvedView typed = new ResolvedView("Demo", "connector.t_view_demo", List.of(
                    new ResolvedView.Column("ratio", "Ratio", type)));

            String sql = ViewDataReader.buildSql(typed, List.of(new ViewFilter("ratio", ">=", "0.5")));

            // A float's text form can be scientific (1e-05, 1e+20), which the numeric text shape
            // rejects — a rejected row is silently dropped. The column is already a float and the
            // operand is a validated number, so compare natively: no column cast, no shape guard.
            assertTrue(sql.contains("\"ratio\" >= ?::float8"), () -> type + ": " + sql);
            assertFalse(sql.contains("::text"), () -> type + " must not round-trip through text: " + sql);
            assertFalse(sql.contains("CASE WHEN"), () -> type + " needs no shape guard: " + sql);
        }
    }
}

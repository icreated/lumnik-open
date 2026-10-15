package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SQL of one View page (#777), driven through the package-visible pure builder: the order is
 * the contract's order key with nulls last, the boundary and the page size are always bound, never
 * concatenated, and the placeholders line up with what the reader binds.
 */
class ViewDataReaderPageSqlTest {

    private static ResolvedView view(String table, String... cols) {
        return new ResolvedView("V", table, Arrays.stream(cols)
            .map(c -> new ResolvedView.Column(c, c, "text")).toList());
    }

    private static final ResolvedView ORDERS = view("connector.t_orders", "order_no", "status");
    private static final ResolvedView ENTITY = view("connector.v_orders", "order_no", "status", "city");
    private static final ViewFilter OPEN = new ViewFilter("status", "=", "open");

    private static long placeholders(String sql) {
        return sql.chars().filter(ch -> ch == '?').count();
    }

    private static ViewDataReader.PageSql page(ResolvedView rv, boolean hasId, List<ViewFilter> f,
                                               ViewCursor.Boundary after) {
        return ViewDataReader.buildPageSql(rv, f, hasId, after);
    }

    // ── relation with an id ─────────────────────────────────────────────────────

    @Test void idRelation_firstPage_ordersByFirstColumnNullsLastThenId_andSelectsTheId() {
        String sql = page(ORDERS, true, List.of(), null).sql();
        assertTrue(sql.contains("ORDER BY \"order_no\" NULLS LAST, \"id\""), sql);
        assertTrue(sql.contains(", \"id\" FROM"), "the last row's id must come back: " + sql);
        assertTrue(sql.endsWith("LIMIT ?"), sql);
        assertFalse(sql.contains("OFFSET"), sql);
        assertEquals(1, placeholders(sql));
    }

    @Test void idRelation_afterABoundary_bindsTheIdThreeTimes_andReadsTheBoundaryByIdInSql() {
        ViewDataReader.PageSql p = page(ORDERS, true, List.of(OPEN), new ViewCursor.Boundary(List.of("42")));
        assertEquals(List.of(42L, 42L, 42L), p.boundaryParams());
        assertEquals(1 + 3 + 1, placeholders(p.sql()), p.sql());
        assertTrue(p.sql().contains("WHERE \"id\" = ?"), p.sql());
        assertFalse(p.sql().contains("42"), "the boundary must be bound, not concatenated: " + p.sql());
    }

    @Test void idRelation_nullFirstColumnOfTheBoundary_isHandledInSql() {
        String sql = page(ORDERS, true, List.of(), new ViewCursor.Boundary(List.of("7"))).sql();
        assertTrue(sql.contains("IS NULL"), sql);   // nulls sort last: the tail of the key walks id alone
    }

    @Test void idRelation_firstColumnIsId_ordersByIdAlone() {
        ViewDataReader.PageSql p = page(view("connector.t_x", "id", "name"), true, List.of(),
            new ViewCursor.Boundary(List.of("9")));
        assertTrue(p.sql().contains("ORDER BY \"id\" LIMIT ?"), p.sql());
        assertTrue(p.sql().contains("\"id\" > ?"), p.sql());
        assertEquals(List.of(9L), p.boundaryParams());
    }

    @Test void idRelation_aBoundaryThatIsNotAnInteger_isTheOneRefusal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> page(ORDERS, true, List.of(), new ViewCursor.Boundary(List.of("not-a-number"))));
        assertEquals(ViewCursor.REFUSAL, e.getMessage());
    }

    @Test void idRelation_aBoundaryOfTheWrongArity_isTheOneRefusal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> page(ORDERS, true, List.of(), new ViewCursor.Boundary(List.of("1", "2"))));
        assertEquals(ViewCursor.REFUSAL, e.getMessage());
    }

    // ── relation without an id (entity view) ────────────────────────────────────

    @Test void noIdRelation_ordersByEveryDeclaredColumnNullsLast() {
        String sql = page(ENTITY, false, List.of(), null).sql();
        assertTrue(sql.contains("ORDER BY \"order_no\" NULLS LAST, \"status\" NULLS LAST, \"city\" NULLS LAST"), sql);
        assertFalse(sql.contains("\"id\""), sql);
    }

    @Test void noIdRelation_afterABoundary_isABoundOffset_andCarriesNoRowText() {
        ViewDataReader.PageSql p = page(ENTITY, false, List.of(), new ViewCursor.Boundary(List.of("40")));
        assertTrue(p.sql().endsWith("LIMIT ? OFFSET ?"), p.sql());
        assertTrue(p.offset());
        assertEquals(40L, p.offsetRows());
        assertTrue(p.boundaryParams().isEmpty());
        assertFalse(p.sql().contains("40"), "the position must be bound: " + p.sql());
    }

    @Test void noIdRelation_firstPage_hasNoOffset() {
        ViewDataReader.PageSql p = page(ENTITY, false, List.of(), null);
        assertFalse(p.offset());
        assertFalse(p.sql().contains("OFFSET"), p.sql());
    }

    @Test void noIdRelation_aPositionThatIsNotANonNegativeInteger_isTheOneRefusal() {
        for (String bad : new String[] {"-1", "x", "1.5", ""}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> page(ENTITY, false, List.of(), new ViewCursor.Boundary(List.of(bad))));
            assertEquals(ViewCursor.REFUSAL, e.getMessage());
        }
    }

    @Test void noIdRelation_aBoundaryOfTheWrongArity_isTheOneRefusal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> page(ENTITY, false, List.of(), new ViewCursor.Boundary(List.of("1", "2"))));
        assertEquals(ViewCursor.REFUSAL, e.getMessage());
    }

    // ── both ────────────────────────────────────────────────────────────────────

    @Test void filtersStayBound_andTheBoundaryIsBoundBeforeThem() {
        // Placeholders are numbered by their position in the text, and the boundary's sub-select
        // sits in FROM: the reader binds the boundary first, then the filters.
        String sql = page(ORDERS, true, List.of(OPEN), new ViewCursor.Boundary(List.of("5"))).sql();
        assertFalse(sql.contains("open"), sql);
        int filter = sql.indexOf("\"status\" = ?");
        assertTrue(filter > 0 && sql.indexOf("\"id\" = ?") < filter, sql);
        assertTrue(sql.indexOf("CASE WHEN") < filter, sql);
    }
}

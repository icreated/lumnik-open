package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pagination contract of a kind:View list (#776), pinned before any SQL changes (#777):
 * page-size bounds, the total order a page boundary can rely on, and the canonical binding a
 * cursor is signed over. Pure — no DB, no HTTP.
 */
class ViewPageContractTest {

    private static ResolvedView view(String table, String... cols) {
        return new ResolvedView("V", table, Arrays.stream(cols)
            .map(c -> new ResolvedView.Column(c, c, "text")).toList());
    }

    private static final ResolvedView ORDERS = view("connector.t_orders", "order_no", "status", "amount");

    private static String bind(long tenant, String scope, ResolvedView rv, List<String> key,
                               List<ViewFilter> filters, int size) {
        return ViewPageContract.binding(tenant, scope, "customer-list", rv, key, filters, size);
    }

    // ── page size ───────────────────────────────────────────────────────────────

    @Test void absentPageSize_isTheDefaultOfFifty() {
        assertEquals(50, ViewPageContract.resolvePageSize(null));
    }

    @Test void pageSize_withinBounds_isKept() {
        assertEquals(1, ViewPageContract.resolvePageSize(1));
        assertEquals(ViewPageContract.MAX_PAGE_SIZE, ViewPageContract.resolvePageSize(ViewPageContract.MAX_PAGE_SIZE));
    }

    @Test void pageSize_outOfBounds_isRefusedNotClamped() {
        // Clamping would answer a question nobody asked: the caller must learn the bound.
        for (int bad : new int[] {0, -1, ViewPageContract.MAX_PAGE_SIZE + 1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ViewPageContract.resolvePageSize(bad));
            assertTrue(e.getMessage().contains("1") && e.getMessage().contains("" + ViewPageContract.MAX_PAGE_SIZE),
                () -> "the refusal must state the bounds: " + e.getMessage());
        }
    }

    @Test void maxPageSize_staysBelowTheFormerHardCut() {
        assertTrue(ViewPageContract.MAX_PAGE_SIZE < 1000);
    }

    // ── ordering key ────────────────────────────────────────────────────────────

    @Test void relationWithId_ordersByFirstColumnThenId() {
        assertEquals(List.of("order_no", "id"), ViewPageContract.orderKey(ORDERS, true));
    }

    @Test void relationWithId_whenFirstColumnIsId_keyIsIdAlone() {
        assertEquals(List.of("id"), ViewPageContract.orderKey(view("connector.t_x", "id", "name"), true));
    }

    @Test void relationWithoutId_ordersByEveryDeclaredColumn() {
        // An entity view (connector.v_*) has no id: the declared columns are the only tie-break.
        assertEquals(List.of("order_no", "status", "amount"),
            ViewPageContract.orderKey(view("connector.v_orders", "order_no", "status", "amount"), false));
    }

    @Test void orderKey_alwaysStartsWithTheFirstDeclaredColumn_unlessItIsId() {
        // The first declared column stays the sort order the user sees (docs/apps/views.md).
        assertEquals("order_no", ViewPageContract.orderKey(ORDERS, true).get(0));
        assertEquals("order_no", ViewPageContract.orderKey(ORDERS, false).get(0));
    }

    @Test void relationHasId_isFalseOnlyForAnEntityView() {
        assertTrue(ViewPageContract.relationHasId("connector.t_orders"));
        assertFalse(ViewPageContract.relationHasId("connector.v_orders"));
        // a hub table whose name merely contains "v_" is still a table
        assertTrue(ViewPageContract.relationHasId("connector.t_v_orders"));
    }

    // ── binding ─────────────────────────────────────────────────────────────────

    private static final List<String> KEY = List.of("order_no", "id");
    private static final List<ViewFilter> NONE = List.of();

    @Test void binding_isDeterministic() {
        assertEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "desk", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_changesWithTenant() {
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(2, "desk", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_changesWithScope() {
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "ops", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_changesWithViewName_evenOnTheSameTableAndColumns() {
        // Two Views over one table with the same columns are two Views: a cursor of one must not
        // open the other.
        assertNotEquals(
            ViewPageContract.binding(1, "desk", "customer-list", ORDERS, KEY, NONE, 50),
            ViewPageContract.binding(1, "desk", "customer-review", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_cannotBeForgedBetweenTheViewNameAndTheScope() {
        assertNotEquals(
            ViewPageContract.binding(1, "b;c", "a", ORDERS, KEY, NONE, 50),
            ViewPageContract.binding(1, "c", "a;b", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_changesWithTable() {
        ResolvedView other = view("connector.t_invoices", "order_no", "status", "amount");
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "desk", other, KEY, NONE, 50));
    }

    @Test void binding_changesWithDeclaredColumns() {
        ResolvedView other = view("connector.t_orders", "order_no", "status");
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "desk", other, KEY, NONE, 50));
    }

    @Test void binding_changesWithColumnOrder() {
        ResolvedView other = view("connector.t_orders", "status", "order_no", "amount");
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "desk", other, KEY, NONE, 50));
    }

    @Test void binding_changesWithOrderKey() {
        assertNotEquals(bind(1, "desk", ORDERS, List.of("order_no", "id"), NONE, 50),
                        bind(1, "desk", ORDERS, List.of("order_no", "status"), NONE, 50));
    }

    @Test void binding_changesWithPageSize() {
        assertNotEquals(bind(1, "desk", ORDERS, KEY, NONE, 50), bind(1, "desk", ORDERS, KEY, NONE, 100));
    }

    @Test void binding_changesWithAnyFilterPart() {
        List<ViewFilter> base = List.of(new ViewFilter("status", "=", "open"));
        String b = bind(1, "desk", ORDERS, KEY, base, 50);
        assertNotEquals(b, bind(1, "desk", ORDERS, KEY, List.of(new ViewFilter("status", "=", "closed")), 50));
        assertNotEquals(b, bind(1, "desk", ORDERS, KEY, List.of(new ViewFilter("status", "~", "open")), 50));
        assertNotEquals(b, bind(1, "desk", ORDERS, KEY, List.of(new ViewFilter("amount", "=", "open")), 50));
        assertNotEquals(b, bind(1, "desk", ORDERS, KEY, NONE, 50));
    }

    @Test void binding_ignoresFilterOrder_andMeansAnd() {
        ViewFilter a = new ViewFilter("status", "=", "open");
        ViewFilter c = new ViewFilter("amount", ">", "10");
        assertEquals(bind(1, "desk", ORDERS, KEY, List.of(a, c), 50),
                     bind(1, "desk", ORDERS, KEY, List.of(c, a), 50));
    }

    @Test void binding_cannotBeForgedByMovingDelimitersBetweenFields() {
        // Without length prefixes both pairs below flatten to the same text: the ';' that ends a
        // field sits inside one value in the first call and between two fields in the second.
        assertNotEquals(
            bind(1, "a;b", view("c", "x"), List.of("x"), NONE, 50),
            bind(1, "a", view("b;c", "x"), List.of("x"), NONE, 50));
        assertNotEquals(
            bind(1, "desk", ORDERS, KEY, List.of(new ViewFilter("a;=", "=", "v")), 50),
            bind(1, "desk", ORDERS, KEY, List.of(new ViewFilter("a", "=", "=;v")), 50));
    }

    @Test void binding_carriesTheContractVersion() {
        assertTrue(bind(1, "desk", ORDERS, KEY, NONE, 50).startsWith("v" + ViewPageContract.VERSION + ":"));
    }
}

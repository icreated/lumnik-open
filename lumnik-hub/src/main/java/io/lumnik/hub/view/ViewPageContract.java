package io.lumnik.hub.view;

import io.lumnik.hub.entity.EntityViewCompiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pagination contract of a kind:View list (#776): what a page may ask for, the total order a
 * page boundary relies on, and the canonical text a cursor is signed over. Pure — the cursor
 * itself and the SQL are #777's; this class only fixes the rules they must obey.
 * See docs/superpowers/specs/2026-10-03-view-pagination-contract-design.md.
 */
public final class ViewPageContract {

    /** Bumped whenever the order key or the binding changes: a cursor of another version is stale. */
    public static final int VERSION = 2;   // 2: an entity's numbers order as numbers (#1338)
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private ViewPageContract() {}

    /** The page size to use: absent means the default; out of bounds is refused, never clamped. */
    public static int resolvePageSize(Integer requested) {
        if (requested == null) return DEFAULT_PAGE_SIZE;
        if (requested < 1 || requested > MAX_PAGE_SIZE)
            throw new IllegalArgumentException(
                "pageSize must be between 1 and " + MAX_PAGE_SIZE + " — got " + requested);
        return requested;
    }

    /**
     * The columns a page orders by, ascending, nulls last. The first declared column leads — it is
     * the order users see today. A relation with an {@code id} (every {@code connector.t_*}) ends
     * on it, which makes the order total. A relation without one (an entity view,
     * {@code connector.v_*}) orders by every declared column; rows identical on all of them are
     * interchangeable, so the order is as good as total for what a page shows.
     */
    public static List<String> orderKey(ResolvedView rv, boolean relationHasId) {
        List<String> declared = rv.columns().stream().map(ResolvedView.Column::name).toList();
        if (!relationHasId) return declared;
        String first = declared.get(0);
        return "id".equals(first) ? List.of("id") : List.of(first, "id");
    }

    /**
     * Whether the relation carries the hub's {@code id BIGSERIAL PRIMARY KEY}: every dynamic or
     * hybrid table the writers create does, an entity view ({@code connector.v_<entity>}) is a
     * fused SELECT and does not.
     */
    public static boolean relationHasId(String table) {
        return EntityViewCompiler.entityNameFromView(table) == null;
    }

    /**
     * The canonical text a cursor's signature covers: contract version, tenant, scope, View
     * identity (the manifest's name, then table + declared columns in order), order key, the filters (AND — order does not
     * matter) and the page size. Every field is length-prefixed, so moving a delimiter between two
     * fields can never produce the same text. It may hold filter values: it is signature INPUT and
     * is never sent to the client.
     */
    public static String binding(long tenant, String scope, String viewName, ResolvedView rv,
                                 List<String> orderKey,
                                 List<ViewFilter> filters, int pageSize) {
        StringBuilder sb = new StringBuilder("v").append(VERSION).append(':');
        field(sb, Long.toString(tenant));
        field(sb, scope);
        field(sb, viewName);
        field(sb, rv.table());
        list(sb, rv.columns().stream().map(ResolvedView.Column::name).toList());
        list(sb, orderKey);
        List<ViewFilter> sorted = new ArrayList<>(filters);
        sorted.sort(Comparator.comparing(ViewFilter::column)
            .thenComparing(ViewFilter::op).thenComparing(ViewFilter::value));
        sb.append(sorted.size()).append('[');
        for (ViewFilter f : sorted) { field(sb, f.column()); field(sb, f.op()); field(sb, f.value()); }
        sb.append(']');
        field(sb, Integer.toString(pageSize));
        return sb.toString();
    }

    private static void list(StringBuilder sb, List<String> items) {
        sb.append(items.size()).append('[');
        for (String s : items) field(sb, s);
        sb.append(']');
    }

    private static void field(StringBuilder sb, String s) {
        sb.append(s.length()).append(':').append(s).append(';');
    }
}

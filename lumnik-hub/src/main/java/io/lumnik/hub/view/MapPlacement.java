package io.lumnik.hub.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The hub's verdict on where each row of a map view sits (#2057). A row is placed when its point's two values are
 * finite numbers, latitude in [-90, 90] and longitude in [-180, 180]; its {@link #FIELD} then carries them, and any
 * other row carries null. The PWA draws what is placed and lists the rest — it never repeats the check, so two clients
 * cannot disagree on which rows are on the map.
 *
 * <p>The point's columns are read for this verdict only: when the view does not show them, they leave the row here,
 * before it is served, so {@code map.point} puts a row on the map without publishing its raw columns.
 */
final class MapPlacement {

    /** The synthetic field a map row carries; not a column, never in {@code columns}. */
    static final String FIELD = "_map";

    private MapPlacement() {}

    /** A placed row's position, as the PWA reads it: {@code {"lat": …, "lon": …}}. */
    record Spot(double lat, double lon) {}

    /** What the render answer says about the map: the point, how many rows could not be placed, or why none can be. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(String point, Integer unplaced, String error) {}

    /**
     * Each row's verdict, in row order — {@code {lat, lon}} or null — with the point's undeclared columns taken out of
     * the rows. Null when the view draws no map. Reads the values as the database returned them, before the wire.
     */
    static List<Spot> take(ResolvedView rv, List<Map<String, Object>> rows) {
        if (rv.map() == null) return null;
        String lat = rv.map().lat();
        String lon = rv.map().lon();
        boolean showsLat = shows(rv, lat);
        boolean showsLon = shows(rv, lon);
        List<Spot> spots = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            spots.add(spot(row.get(lat), row.get(lon)));
            if (!showsLat) row.remove(lat);
            if (!showsLon) row.remove(lon);
        }
        return spots;
    }

    /** Puts each verdict on its row and sums up the map; null when the manifest asks for no map. */
    static Summary attach(String point, ResolvedView rv, List<Map<String, Object>> rows, List<Spot> spots) {
        if (point == null) return null;
        if (spots == null) {
            return new Summary(point, null, "point '" + point + "' is not usable on " + rv.table()
                + " now: its two columns are not both numbers — the entity changed since this view was written");
        }
        int unplaced = 0;
        for (int i = 0; i < rows.size(); i++) {
            Spot spot = spots.get(i);
            if (spot == null) unplaced++;
            rows.get(i).put(FIELD, spot);
        }
        return new Summary(point, unplaced, null);
    }

    static Spot spot(Object lat, Object lon) {
        if (!(lat instanceof Number a) || !(lon instanceof Number o)) return null;
        double la = a.doubleValue();
        double lo = o.doubleValue();
        if (!Double.isFinite(la) || !Double.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) return null;
        return new Spot(la, lo);
    }

    private static boolean shows(ResolvedView rv, String column) {
        return rv.columns().stream().anyMatch(c -> c.name().equals(column));
    }
}

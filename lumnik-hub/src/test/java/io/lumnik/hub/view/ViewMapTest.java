package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #2057: a view drawn on a map — the manifest key, the point it resolves to, and the hub's verdict per row. */
class ViewMapTest {

    private static final String TABLE = "connector.v_customer";
    private static final SchemaCard.Point SITE = new SchemaCard.Point("site", "latitude", "longitude");

    private static String yaml(String map) {
        return """
            kind: View
            metadata: { name: customers-map }
            spec:
              scope: sales
              table: connector.v_customer
              columns: [customer_no, name]
            """ + map;
    }

    private static SchemaCard card(List<SchemaCard.Point> points, String... extraColumns) {
        List<SchemaCard.Column> cols = new ArrayList<>(List.of(
            new SchemaCard.Column("customer_no", "text", null),
            new SchemaCard.Column("name", "text", null),
            new SchemaCard.Column("latitude", "numeric", null),
            new SchemaCard.Column("longitude", "numeric", null)));
        for (String c : extraColumns) cols.add(new SchemaCard.Column(c, "text", null));
        return new SchemaCard("sales", List.of(new SchemaCard.Table(TABLE, "Customers", cols, null, points)));
    }

    // ── the manifest ────────────────────────────────────────────────────────────────────────────

    @Test
    void theMapPointIsParsedAndAViewWithoutOneHasNone() {
        ViewManifestParser parser = new ViewManifestParser();
        assertEquals("site", parser.parse(yaml("  map: { point: site }\n")).mapPoint());
        assertNull(parser.parse(yaml("")).mapPoint());
    }

    @Test
    void aMapThatIsNotAMappingOrNamesNoPointIsRefused() {
        ViewManifestParser parser = new ViewManifestParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(yaml("  map: site\n")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(yaml("  map: {}\n")));
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
            () -> parser.parse(yaml("  map: { point: \"si te\" }\n")));
        assertTrue(bad.getMessage().startsWith("spec.map.point:"), bad.getMessage());
    }

    @Test
    void anUnknownKeyUnderMapIsNamed() {
        assertEquals(List.of("spec.map.radius"),
            new ViewManifestParser().unknownKeys(yaml("  map: { point: site, radius: 20 }\n")));
    }

    // ── the point it resolves to ────────────────────────────────────────────────────────────────

    @Test
    void theMapResolvesToTheCardsPointAndItsColumnsAreSelectedAfterTheShownOnes() {
        ViewManifest m = new ViewManifestParser().parse(yaml("  map: { point: site }\n"));
        ResolvedView rv = new ViewResolver().resolve(m, card(List.of(SITE)));
        assertEquals(SITE, rv.map());
        assertEquals(List.of("customer_no", "name", "latitude", "longitude"), rv.selected());
        assertTrue(ViewDataReader.buildSql(rv, List.of()).startsWith(
            "SELECT \"customer_no\", \"name\", \"latitude\", \"longitude\" FROM"));
    }

    @Test
    void aViewWithoutAMapSelectsExactlyItsColumns() {
        ResolvedView rv = new ViewResolver().resolve(new ViewManifestParser().parse(yaml("")), card(List.of(SITE)));
        assertNull(rv.map());
        assertEquals(List.of("customer_no", "name"), rv.selected());
    }

    @Test
    void aPointTheCardDoesNotOfferResolvesToNoMapAndTheApplyDoorRefusesIt() {
        ViewManifest m = new ViewManifestParser().parse(yaml("  map: { point: depot }\n"));
        SchemaCard card = card(List.of(SITE));
        ResolvedView rv = new ViewResolver().resolve(m, card);
        assertNull(rv.map(), "a saved view still renders as a list");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new ViewResolver().resolveForApply(m, card));
        assertTrue(e.getMessage().startsWith(
            "spec.map.point: table connector.v_customer offers no point 'depot' (offered: site)"), e.getMessage());
    }

    @Test
    void aTableWithAColumnNamedLikeTheMapFieldRefusesAMap() {
        ViewManifest m = new ViewManifestParser().parse(yaml("  map: { point: site }\n"));
        SchemaCard card = card(List.of(SITE), "_map");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new ViewResolver().resolveForApply(m, card));
        assertTrue(e.getMessage().contains("has a column named '_map'"), e.getMessage());
    }

    // ── the verdict per row ─────────────────────────────────────────────────────────────────────

    static Stream<Arguments> values() {
        return Stream.of(
            Arguments.of(new BigDecimal("45.7640"), new BigDecimal("4.8357"), true),
            Arguments.of(-33.8688, 151.2093, true),
            Arguments.of(90, -180, true),
            Arguments.of(null, 4.8, false),
            Arguments.of(45.7, null, false),
            Arguments.of("45.7", 4.8, false),
            Arguments.of(91, 4.8, false),
            Arguments.of(4.8, 181, false),
            Arguments.of(Double.NaN, 4.8, false),
            Arguments.of(45.7, Double.POSITIVE_INFINITY, false));
    }

    @ParameterizedTest
    @MethodSource("values")
    void aRowIsPlacedOnlyWithTwoFiniteNumbersInRange(Object lat, Object lon, boolean placed) {
        MapPlacement.Spot spot = MapPlacement.spot(lat, lon);
        assertEquals(placed, spot != null);
        if (placed) assertEquals(new MapPlacement.Spot(((Number) lat).doubleValue(), ((Number) lon).doubleValue()), spot);
    }

    private static Map<String, Object> row(String no, Object lat, Object lon) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customer_no", no);
        row.put("latitude", lat);
        row.put("longitude", lon);
        return row;
    }

    @Test
    void eachRowCarriesItsVerdictAndThePointsUnshownColumnsLeaveIt() {
        ViewManifest m = new ViewManifestParser().parse(yaml("  map: { point: site }\n"));
        ResolvedView rv = new ViewResolver().resolve(m, card(List.of(SITE)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("C-1", 45.76, 4.83), row("C-2", null, 4.8)));

        List<MapPlacement.Spot> spots = MapPlacement.take(rv, rows);
        MapPlacement.Summary summary = MapPlacement.attach(m.mapPoint(), rv, rows, spots);

        assertEquals(new MapPlacement.Summary("site", 1, null), summary);
        Map<String, Object> expectedPlaced = new HashMap<>();
        expectedPlaced.put("customer_no", "C-1");
        expectedPlaced.put("_map", new MapPlacement.Spot(45.76, 4.83));
        assertEquals(expectedPlaced, rows.get(0));
        assertTrue(rows.get(1).containsKey("_map"));
        assertNull(rows.get(1).get("_map"), "an unplaced row says so with null, not by missing the field");
        assertFalse(rows.get(1).containsKey("latitude"));
    }

    @Test
    void aColumnTheViewShowsStaysInTheRow() {
        ViewManifest m = new ViewManifest("v", "sales", TABLE, null, List.of("customer_no", "latitude"), List.of(), "site");
        ResolvedView rv = new ViewResolver().resolve(m, card(List.of(SITE)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("C-1", 45.76, 4.83)));
        MapPlacement.attach(m.mapPoint(), rv, rows, MapPlacement.take(rv, rows));
        assertTrue(rows.get(0).containsKey("latitude"));
        assertFalse(rows.get(0).containsKey("longitude"));
    }

    @Test
    void aPointNoLongerUsableIsReportedNotThrown() {
        ViewManifest m = new ViewManifestParser().parse(yaml("  map: { point: site }\n"));
        ResolvedView rv = new ViewResolver().resolve(m, card(List.of()));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("C-1", 45.76, 4.83)));

        MapPlacement.Summary summary = MapPlacement.attach(m.mapPoint(), rv, rows, MapPlacement.take(rv, rows));

        assertNull(summary.unplaced());
        assertTrue(summary.error().startsWith("point 'site' is not usable on connector.v_customer now"), summary.error());
        assertFalse(rows.get(0).containsKey("_map"));
    }

    @Test
    void aViewWithoutAMapGetsNoSummaryAndNoField() {
        ViewManifest m = new ViewManifestParser().parse(yaml(""));
        ResolvedView rv = new ViewResolver().resolve(m, card(List.of(SITE)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("C-1", 45.76, 4.83)));
        assertNull(MapPlacement.attach(m.mapPoint(), rv, rows, MapPlacement.take(rv, rows)));
        assertFalse(rows.get(0).containsKey("_map"));
    }
}

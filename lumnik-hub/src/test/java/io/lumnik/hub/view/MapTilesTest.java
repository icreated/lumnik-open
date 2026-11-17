package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #2057: the map's tile server — none by default, the operator's own when named, a bad value stopping the hub. */
class MapTilesTest {

    private static MapTiles of(String tiles, String attribution) {
        MapTiles t = new MapTiles();
        t.tiles = Optional.ofNullable(tiles);
        t.attribution = Optional.ofNullable(attribution);
        t.read();
        return t;
    }

    @Test
    void unsetOrBlankMeansNoBackgroundAndNoCspSource() {
        assertTrue(of(null, null).background().isEmpty());
        assertTrue(of("  ", "OSM").background().isEmpty());
        assertTrue(of(null, null).cspSource().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png, https://tile.openstreetmap.org",
        "https://tiles.example.org:8443/osm/{z}/{x}/{y}.png?key=public, https://tiles.example.org:8443",
        "http://localhost:8081/{z}/{y}/{x}.png, http://localhost:8081",
    })
    void theCspSourceIsTheTemplatesOriginOnly(String template, String origin) {
        MapTiles t = of(template, "© OpenStreetMap");
        assertEquals(new MapTiles.Background(template, "© OpenStreetMap"), t.background().orElseThrow());
        assertEquals(origin, t.cspSource().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://tile.openstreetmap.org/{z}/{x}/{y}.png",          // http to a remote host
        "https://tile.openstreetmap.org/{z}/{x}.png",              // {y} missing
        "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",      // placeholder in the host
        "https://tile.example.org/{z}/{x}/{y}/{r}.png",            // a placeholder the map does not fill
        "https://user:pw@tile.example.org/{z}/{x}/{y}.png",        // user info
        "https://tile.example.org/{z}/{x}/{y}.png' ; script-src *", // a quote: would widen the CSP
        "javascript:alert(1)//{z}/{x}/{y}",
        "https://tile.example.org/{z}/{x}/{y}/{}.png",            // a stray brace
        "tile.example.org/{z}/{x}/{y}.png",
    })
    void aBadTemplateStopsTheHubNamingTheProperty(String template) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> of(template, null));
        assertTrue(e.getMessage().startsWith("lumnik.map.tiles"), e.getMessage());
    }

    @Test
    void anAttributionPastItsCapIsRefused() {
        assertThrows(IllegalStateException.class,
            () -> of("https://t.example.org/{z}/{x}/{y}.png", "x".repeat(MapTiles.MAX_ATTRIBUTION + 1)));
    }
}

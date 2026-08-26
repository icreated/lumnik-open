package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlsTest {

    @Test
    void joins_with_exactly_one_slash() {
        assertEquals("http://api/items", Urls.join("http://api", "/items"));   // already clean
        assertEquals("http://api/items", Urls.join("http://api", "items"));    // missing leading slash
        assertEquals("http://api/items", Urls.join("http://api/", "/items"));  // trailing slash on base
        assertEquals("http://api/items", Urls.join("http://api/", "items"));   // both
    }

    @Test
    void blank_or_null_path_returns_base_without_trailing_slash() {
        assertEquals("http://api", Urls.join("http://api/", ""));
        assertEquals("http://api", Urls.join("http://api", null));
        assertEquals("http://api", Urls.join("http://api", "   "));
    }

    @Test
    void collapses_multiple_leading_slashes_and_trailing_base_slashes() {
        assertEquals("http://api/items", Urls.join("http://api", "//items"));
        assertEquals("http://api/items", Urls.join("http://api", "///items"));
        assertEquals("http://api/items", Urls.join("http://api//", "//items"));
    }

    @Test
    void trims_surrounding_whitespace_on_path() {
        assertEquals("http://api/items", Urls.join("http://api", "  /items  "));
    }

    @Test
    void fails_fast_on_null_base() {
        assertThrows(NullPointerException.class, () -> Urls.join(null, "/items"));
    }
}

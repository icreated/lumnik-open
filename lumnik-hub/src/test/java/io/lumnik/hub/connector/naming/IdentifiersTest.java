package io.lumnik.hub.connector.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentifiersTest {

    @Test void clean_ascii_is_unchanged() {
        assertEquals("order_items", Identifiers.slug("order_items", "x"));
        assertEquals("order_items", Identifiers.slug("/order-items", "x"));
    }

    @Test void accents_and_apostrophes_are_romanized_not_dropped() {
        // the "user-friendly rename" case — keep the meaning, lose only the diacritics/punctuation
        assertEquals("modele_d_adaptation", Identifiers.slug("Modèle d'adaptation", "x"));
        assertEquals("cafe", Identifiers.slug("café", "x"));
    }

    @Test void cjk_is_romanized_to_a_nonempty_ascii_slug() {
        String s = Identifiers.slug("日本語", "fallback");
        assertFalse(s.isBlank(), "CJK should romanize, not vanish");
        assertNotEquals("fallback", s, "CJK should romanize, not hit the fallback");
        assertTrue(s.matches("[a-z0-9_]+"), "must be a safe identifier, got: " + s);
        System.out.println("日本語 -> " + s);
    }

    @Test void null_uses_the_bare_fallback() {
        assertEquals("x", Identifiers.slug(null, "x"));
    }

    @Test void digit_leading_slug_is_prefixed_to_stay_a_valid_identifier() {
        // unquoted SQL identifiers (and YAML scalars) must not start with a digit
        assertEquals("_1_things", Identifiers.slug("/1/things", "x"));
        assertEquals("_123", Identifiers.slug("123", "x"));
        assertEquals("order_1", Identifiers.slug("order-1", "x"));  // letter-leading unchanged
    }

    @Test void empty_slug_fallback_is_collision_resistant() {
        // Distinct names that both collapse to nothing must NOT share one fallback — otherwise distinct
        // connectors would write the same ext.* table and mix/overwrite data. The fallback carries a
        // stable hash of the raw input.
        String a = Identifiers.slug("🎉", "table");
        String b = Identifiers.slug("™§¶", "table");

        assertTrue(a.startsWith("table_"), a);
        assertTrue(a.matches("[a-z0-9_]+"), "must stay a safe identifier: " + a);
        assertNotEquals(a, b, "distinct collapsing names must yield distinct fallbacks");
        assertEquals(a, Identifiers.slug("🎉", "table"), "fallback must be stable across calls");
    }

    @Test void unique_disambiguates_collisions_swagger_style() {
        Identifiers.Unique u = new Identifiers.Unique();
        assertEquals("person", u.take("person"));
        assertEquals("person_2", u.take("person"));
        assertEquals("person_3", u.take("person"));
        assertEquals("order", u.take("order"));   // unrelated name keeps its bare form
    }
}

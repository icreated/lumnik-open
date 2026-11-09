package io.lumnik.hub.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPatternsTest {

    @Test
    void an_exact_type_matches_only_itself() {
        assertTrue(EventPatterns.matches("decision.raised", "decision.raised"));
        assertFalse(EventPatterns.matches("decision.raised", "decision.raised.x"));
    }

    @Test
    void a_prefix_matches_what_follows_its_dot() {
        assertTrue(EventPatterns.matches("decision.*", "decision.raised"));
        assertTrue(EventPatterns.matches("decision.cold.*", "decision.cold.room-1"));
    }

    @Test
    void a_prefix_does_not_match_a_type_that_only_shares_its_letters() {
        assertFalse(EventPatterns.matches("decision.cold.*", "decision.coldroom-x"));
        assertFalse(EventPatterns.matches("decision.*", "decisions.raised"));
    }

    @Test
    void a_prefix_needs_something_after_the_dot() {
        assertFalse(EventPatterns.matches("decision.*", "decision."));
        assertFalse(EventPatterns.matches("decision.*", "decision"));
    }

    @Test
    void a_star_anywhere_but_the_final_dot_star_matches_nothing() {
        assertFalse(EventPatterns.matches("decision.cold-room-*", "decision.cold-room-1"));
        assertFalse(EventPatterns.matches("*", "decision.raised"));
        assertFalse(EventPatterns.matches("dec*.raised", "decision.raised"));
    }

    @Test
    void a_null_pattern_or_type_matches_nothing() {
        assertFalse(EventPatterns.matches(null, "decision.raised"));
        assertFalse(EventPatterns.matches("decision.*", null));
    }

    @Test
    void accepted_forms_have_no_complaint() {
        assertNull(EventPatterns.refusal("decision.raised"));
        assertNull(EventPatterns.refusal("decision.*"));
        assertNull(EventPatterns.refusal("data.row.*"));
    }

    @Test
    void a_star_anywhere_else_is_refused_naming_the_accepted_forms() {
        for (String bad : new String[] {"decision.cold-room-*", "*", ".*", "decision.*.x", "dec*", "decision.**", "decision*"}) {
            String why = EventPatterns.refusal(bad);
            assertTrue(why != null && why.contains("'" + bad + "'")
                    && why.contains("exact event type") && why.contains("prefix.*"), bad + " → " + why);
        }
    }

    @Test
    void a_blank_pattern_is_refused() {
        assertNotNull(EventPatterns.refusal(" "));
    }
}

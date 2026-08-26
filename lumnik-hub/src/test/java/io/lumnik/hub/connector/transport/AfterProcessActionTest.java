package io.lumnik.hub.connector.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * parse() must reconcile the manifest/validator vocabulary (archive | delete | move | none) with the
 * runtime enum (LEAVE | MOVE | DELETE | CUSTOM_KOTLIN). In particular `archive` must relocate (MOVE),
 * not silently fall back to LEAVE, and `none` must mean leave-in-place.
 */
class AfterProcessActionTest {

    @Test void none_means_leave() {
        assertEquals(AfterProcessAction.LEAVE, AfterProcessAction.parse("none"));
    }

    @Test void leave_alias_is_accepted() {
        assertEquals(AfterProcessAction.LEAVE, AfterProcessAction.parse("leave"));
    }

    @Test void archive_relocates_as_MOVE() {
        assertEquals(AfterProcessAction.MOVE, AfterProcessAction.parse("archive"));
    }

    @Test void move_is_MOVE() {
        assertEquals(AfterProcessAction.MOVE, AfterProcessAction.parse("move"));
    }

    @Test void delete_is_DELETE() {
        assertEquals(AfterProcessAction.DELETE, AfterProcessAction.parse("delete"));
    }

    @Test void case_and_hyphen_insensitive() {
        assertEquals(AfterProcessAction.CUSTOM_KOTLIN, AfterProcessAction.parse("Custom-Kotlin"));
        assertEquals(AfterProcessAction.MOVE, AfterProcessAction.parse("ARCHIVE"));
    }

    @Test void null_blank_unknown_default_to_leave() {
        assertEquals(AfterProcessAction.LEAVE, AfterProcessAction.parse(null));
        assertEquals(AfterProcessAction.LEAVE, AfterProcessAction.parse(""));
        assertEquals(AfterProcessAction.LEAVE, AfterProcessAction.parse("wat"));
    }
}

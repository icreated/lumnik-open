package io.lumnik.hub.api.sources;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The refusal mapper exists so a refusal never reaches the caller as an unreadable 500. It must
 * not become the cause of one: {@code Map.of} rejects null values, and an
 * {@code IllegalArgumentException} raised with no message is perfectly legal Java.
 */
class CsvIngestResourceRefusalTest {

    private final CsvIngestResource resource = new CsvIngestResource();

    @Test
    void a_refusal_with_a_message_answers_400_and_says_why() {
        Response r = resource.refused(new IllegalArgumentException("point at a single file"));

        assertEquals(400, r.getStatus());
        assertEquals("point at a single file", asError(r));
    }

    @Test
    void a_refusal_without_a_message_still_answers_400() {
        Response r = resource.refused(new IllegalArgumentException());

        assertEquals(400, r.getStatus(), "a missing message must not turn the refusal into a 500");
        assertNotNull(asError(r), "the error field must survive a null message");
        assertFalse(asError(r).isBlank(), "an empty reason teaches nothing");
    }

    @SuppressWarnings("unchecked")
    private String asError(Response r) {
        return ((Map<String, String>) r.getEntity()).get("error");
    }
}

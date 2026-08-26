package io.lumnik.hub.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventOutboxRowTest {

    private static final Map<String, Object> P = Map.of("k", "v");

    @Test
    void ofFullRejectsNullForEveryNotNullColumn() {
        assertThrows(NullPointerException.class, () ->
            EventOutboxRow.ofFull(null, null, "t", "system", null, null, P, null, Instant.now()),
            "tenantId is NOT NULL");
        assertThrows(NullPointerException.class, () ->
            EventOutboxRow.ofFull(1L, null, null, "system", null, null, P, null, Instant.now()),
            "eventType is NOT NULL");
        assertThrows(NullPointerException.class, () ->
            EventOutboxRow.ofFull(1L, null, "t", null, null, null, P, null, Instant.now()),
            "source is NOT NULL");
        assertThrows(NullPointerException.class, () ->
            EventOutboxRow.ofFull(1L, null, "t", "system", null, null, null, null, Instant.now()),
            "payload is NOT NULL");
        assertThrows(NullPointerException.class, () ->
            EventOutboxRow.ofFull(1L, null, "t", "system", null, null, P, null, null),
            "occurred_at is NOT NULL");
    }

    @Test
    void ofFullAllowsTheNullableColumns() {
        Instant when = Instant.now();
        EventOutboxRow r = EventOutboxRow.ofFull(1L, null, "t", "src", null, null, P, null, when);
        assertNull(r.getOrgId());
        assertNull(r.getEntity());
        assertNull(r.getEntityKey());
        assertNull(r.getDedupKey());
        assertEquals("src", r.getSource());
        assertEquals(when, r.getOccurredAt());
        assertNotNull(r.getCreatedAt());
    }

    @Test
    void ofDefaultsSourceToSystemAndStampsOccurredAt() {
        EventOutboxRow r = EventOutboxRow.of(1L, "t", P);
        assertEquals("system", r.getSource());
        assertNotNull(r.getOccurredAt());
        assertEquals(1L, r.getTenantId());
    }
}

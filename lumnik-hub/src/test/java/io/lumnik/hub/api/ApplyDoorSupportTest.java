package io.lumnik.hub.api;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyDoorSupportTest {

    @Test
    void refuseUnknownKeysIsEmptyWhenNothingIsUnknown() {
        assertTrue(ApplyDoorSupport.refuseUnknownKeys("View", List.of(), "root {kind}").isEmpty());
    }

    @Test
    void refuseUnknownKeysNamesTheKindAndTheHonoredSurface() {
        Response r = ApplyDoorSupport.refuseUnknownKeys("View", List.of("spec.titel"), "root {kind}")
            .orElseThrow();
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("unknown key(s) in the manifest: spec.titel — kind:View honors root {kind}",
            body.get("error"));
    }

    @Test
    void refuseReservedOrInvalidSlugIsEmptyForAValidUnreservedName() {
        assertTrue(ApplyDoorSupport.refuseReservedOrInvalidSlug("clients-desk", Set.of("apply")).isEmpty());
    }

    @Test
    void refuseReservedOrInvalidSlugRefusesAnUnsafeCharacter() {
        Response r = ApplyDoorSupport.refuseReservedOrInvalidSlug("clients/desk", Set.of("apply"))
            .orElseThrow();
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("metadata.name must be a URL-safe slug ([a-z0-9_-], starting with a letter "
            + "or digit) and not one of [apply]", body.get("error"));
    }

    @Test
    void refuseReservedOrInvalidSlugRefusesAReservedName() {
        Response r = ApplyDoorSupport.refuseReservedOrInvalidSlug("apply", Set.of("apply")).orElseThrow();
        assertEquals(400, r.getStatus());
    }
}

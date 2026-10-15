package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sealed page cursor (#777): opaque (the boundary values are not readable), integrity
 * protected, and bound to the query it was issued for — every failure is the SAME refusal, so a
 * caller cannot tell a forged token from a stale one. Pure: no DB, no HTTP.
 */
class ViewCursorTest {

    private static final byte[] KEY = filled(1);
    private static final byte[] OTHER_KEY = filled(2);
    private static final String BINDING = "v1:binding-of-one-query";
    private static final ViewCursor.Boundary BOUNDARY = new ViewCursor.Boundary(Arrays.asList("ACME-4242", null, "x"));

    /** A throwaway 256-bit test key — built, not spelled, so no literal reads as a credential. */
    private static byte[] filled(int b) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) b);
        return k;
    }

    @Test void roundTrip_returnsTheBoundaryItWasSealedWith() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        assertEquals(BOUNDARY, ViewCursor.open(KEY, BINDING, token));
    }

    @Test void nullAndEmptyValuesSurviveTheRoundTrip() {
        ViewCursor.Boundary b = new ViewCursor.Boundary(Arrays.asList(null, "", " "));
        assertEquals(b, ViewCursor.open(KEY, BINDING, ViewCursor.seal(KEY, BINDING, b)));
    }

    @Test void token_isUrlSafe() {
        assertTrue(ViewCursor.seal(KEY, BINDING, BOUNDARY).matches("[A-Za-z0-9_-]+"));
    }

    @Test void token_doesNotShowTheBoundaryValuesNorTheBinding() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.ISO_8859_1);
        assertFalse(token.contains("ACME") || decoded.contains("ACME-4242"), "boundary value readable");
        assertFalse(decoded.contains("binding-of-one-query"), "binding readable");
    }

    @Test void sealingTwiceGivesTwoTokens_butBothOpen() {
        String a = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        String b = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        assertNotEquals(a, b);
        assertEquals(BOUNDARY, ViewCursor.open(KEY, BINDING, b));
    }

    @Test void anotherBinding_isRefused() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        assertRefused(() -> ViewCursor.open(KEY, "v1:binding-of-another-query", token));
    }

    @Test void anotherKey_isRefused() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        assertRefused(() -> ViewCursor.open(OTHER_KEY, BINDING, token));
    }

    @Test void everyFlippedByte_isRefused() {
        byte[] raw = Base64.getUrlDecoder().decode(ViewCursor.seal(KEY, BINDING, BOUNDARY));
        for (int i = 0; i < raw.length; i++) {
            byte[] copy = raw.clone();
            copy[i] ^= 0x01;
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
            assertRefused(() -> ViewCursor.open(KEY, BINDING, forged));
        }
    }

    @Test void truncatedAndExtendedTokens_areRefused() {
        byte[] raw = Base64.getUrlDecoder().decode(ViewCursor.seal(KEY, BINDING, BOUNDARY));
        String shorter = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(raw, raw.length - 1));
        String longer = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(raw, raw.length + 1));
        assertRefused(() -> ViewCursor.open(KEY, BINDING, shorter));
        assertRefused(() -> ViewCursor.open(KEY, BINDING, longer));
    }

    @Test void garbage_isRefused() {
        for (String bad : new String[] {"", " ", "not a token", "AAAA", "%%%", "a".repeat(5000)})
            assertRefused(() -> ViewCursor.open(KEY, BINDING, bad));
    }

    @Test void everyRefusal_isTheSameMessage_noOracle() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        String wrongBinding = message(() -> ViewCursor.open(KEY, "other", token));
        String garbage = message(() -> ViewCursor.open(KEY, BINDING, "garbage"));
        String wrongKey = message(() -> ViewCursor.open(OTHER_KEY, BINDING, token));
        assertEquals(wrongBinding, garbage);
        assertEquals(wrongBinding, wrongKey);
        assertTrue(wrongBinding.contains("first page"), "the refusal must say how to recover: " + wrongBinding);
    }

    @Test void aSealedValueNeverAppearsInTheRefusal() {
        String token = ViewCursor.seal(KEY, BINDING, BOUNDARY);
        assertFalse(message(() -> ViewCursor.open(KEY, "other", token)).contains("ACME"));
    }

    @Test void aSealedToken_isAlwaysSmallEnoughToBeOpenedAgain() {
        // The token holds identifiers and positions, never row text: the biggest realistic boundary
        // is far below what open() accepts.
        String token = ViewCursor.seal(KEY, BINDING, new ViewCursor.Boundary(List.of(Long.toString(Long.MAX_VALUE))));
        assertTrue(token.length() < 200, "token is " + token.length() + " chars");
        assertEquals(Long.toString(Long.MAX_VALUE), ViewCursor.open(KEY, BINDING, token).values().get(0));
    }

    @Test void sealRefusesToEmitWhatOpenWouldRefuse() {
        // The invariant: an issued cursor is always reusable. A boundary too large for open() is a
        // bug at the issuer, caught there, not a 400 on the client's next click.
        ViewCursor.Boundary huge = new ViewCursor.Boundary(List.of("x".repeat(ViewCursor.MAX_TOKEN_CHARS)));
        assertThrows(IllegalArgumentException.class, () -> ViewCursor.seal(KEY, BINDING, huge));
    }

    private static void assertRefused(Runnable r) {
        assertThrows(IllegalArgumentException.class, r::run);
    }

    private static String message(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }
}

package io.lumnik.hub.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * The page cursor of a kind:View list (#777): where the next page starts, sealed so the client can
 * hold it but neither read nor change it. AES-GCM gives both at once — the boundary values are
 * ciphertext, and the query binding ({@link ViewPageContract#binding}) is the authenticated data,
 * so a cursor opens only for the exact View, tenant, scope, filters, order and page size it was
 * issued for, without the binding ever travelling. Every way to fail is the SAME refusal: a caller
 * must not learn whether a token was forged, stale, or issued for another query.
 *
 * <p>A cursor authorizes nothing — the caller re-runs scope, RLS and filter validation on every
 * page. Pure: the key is handed in, so no DB, no CDI. See the spec
 * {@code 2026-10-03-view-pagination-contract-design.md}.
 */
public final class ViewCursor {

    /**
     * Where the next page starts, as short identifiers only — never row text, so a token stays far
     * below {@link #MAX_TOKEN_CHARS} whatever the rows hold. For a relation with an {@code id}: its
     * one value is the last served row's id. Without one: its one value is the position (rows
     * already served) the next page starts at.
     */
    public record Boundary(List<String> values) {
        public Boundary {
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    static final String REFUSAL =
        "this page link is no longer valid — reload the view from its first page";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** What {@link #open} accepts. {@link #seal} refuses to emit more: an issued cursor is always reusable. */
    static final int MAX_TOKEN_CHARS = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ViewCursor() {}

    /** The opaque, URL-safe token for {@code boundary}, bound to {@code binding}. */
    public static String seal(byte[] key, String binding, Boundary boundary) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(JSON.writeValueAsBytes(new Payload(boundary.values())));
            byte[] out = Arrays.copyOf(iv, IV_BYTES + ct.length);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(out);
            if (token.length() > MAX_TOKEN_CHARS)
                throw new IllegalArgumentException("cursor boundary too large to seal: " + token.length() + " chars");
            return token;
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("cannot seal a view cursor: " + e.getMessage(), e);
        }
    }

    /** The boundary inside {@code token}, or the one refusal for anything that is not exactly it. */
    public static Boundary open(byte[] key, String binding, String token) {
        if (token == null || token.length() > MAX_TOKEN_CHARS) throw refused();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            if (raw.length <= IV_BYTES) throw refused();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            c.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            Payload p = JSON.readValue(c.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), Payload.class);
            return new Boundary(p.k());
        } catch (GeneralSecurityException | IllegalArgumentException | IOException e) {
            throw refused();
        }
    }

    private static IllegalArgumentException refused() {
        return new IllegalArgumentException(REFUSAL);
    }

    private record Payload(List<String> k) {}
}

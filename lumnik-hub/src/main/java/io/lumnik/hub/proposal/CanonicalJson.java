package io.lumnik.hub.proposal;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The canonical form the catalogue lock digests (contract section 4): object keys sorted by code
 * point, compact separators, strings unescaped beyond what JSON requires. One implementation per
 * language is unavoidable; the Python guard's {@code digest()} is the other, and the lock is the
 * proof they agree.
 *
 * <p>Numbers are integers only, within ±(2^53 − 1) — what the site's {@code JSON.parse} keeps exactly.
 * The guard refuses any other number when the catalogue changes, so the
 * {@code IllegalArgumentException}s below are the last net, not the first: they keep a float, or an
 * integer past that range, that somehow reached the hub from being digested to a value no other
 * implementation would produce.
 */
final class CanonicalJson {

    private static final Comparator<String> BY_CODE_POINT =
        (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());

    /** Number.MAX_SAFE_INTEGER: the largest integer the site's JSON.parse reads exactly. */
    private static final BigInteger SAFE_INTEGER = BigInteger.valueOf((1L << 53) - 1);

    private CanonicalJson() {}

    static String digest(JsonNode node) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(of(node).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String of(JsonNode node) {
        StringBuilder b = new StringBuilder();
        write(node, b);
        return b.toString();
    }

    private static void write(JsonNode n, StringBuilder b) {
        if (n.isObject()) {
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            keys.sort(BY_CODE_POINT);
            b.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) b.append(',');
                b.append(JsonText.quote(keys.get(i))).append(':');
                write(n.get(keys.get(i)), b);
            }
            b.append('}');
        } else if (n.isArray()) {
            b.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) b.append(',');
                write(n.get(i), b);
            }
            b.append(']');
        } else if (n.isTextual()) {
            b.append(JsonText.quote(n.asText()));
        } else if (n.isIntegralNumber()) {
            if (n.bigIntegerValue().abs().compareTo(SAFE_INTEGER) > 0) {
                throw new IllegalArgumentException("no canonical form for an integer outside +/-(2^53 - 1) in a catalogue");
            }
            b.append(n.bigIntegerValue());
        } else if (n.isBoolean()) {
            b.append(n.booleanValue());
        } else if (n.isNull()) {
            b.append("null");
        } else {
            throw new IllegalArgumentException("no canonical form for a " + n.getNodeType() + " in a catalogue");
        }
    }
}

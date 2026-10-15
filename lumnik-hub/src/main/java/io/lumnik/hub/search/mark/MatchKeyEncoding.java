package io.lumnik.hub.search.mark;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Length-prefixed, unambiguous framing of a {@code match:} entity's group key tuple (design §2/§9):
 * {@code "<n>|<len1>:<v1>|<len2>:<v2>|…"} where {@code n} is the column count and each
 * {@code len_i} is the value's UTF-8 byte length.
 *
 * <p>Deliberately never a delimiter-and-escape scheme: the length prefix fixes each component's
 * boundary regardless of what bytes it contains, so no character needs escaping and no escaping bug
 * can reintroduce a collision — the risk a {@code \|}-join-with-escaping always carries. The leading
 * count also guards against two tuples of different arity colliding on an overlapping suffix.
 *
 * <p>{@code canonical_key} is never decoded, only compared for equality: a caller re-encodes a
 * candidate row's match tuple the same deterministic way and does a string {@code =}.
 */
public final class MatchKeyEncoding {

    private MatchKeyEncoding() {}

    public static String encode(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(values.size());
        for (String v : values) {
            int byteLen = v.getBytes(StandardCharsets.UTF_8).length;
            sb.append('|').append(byteLen).append(':').append(v);
        }
        return sb.toString();
    }
}

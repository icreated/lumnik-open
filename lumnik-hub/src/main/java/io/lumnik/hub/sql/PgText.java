package io.lumnik.hub.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.HashMap;
import java.util.Map;

/**
 * What a PostgreSQL text column can hold, for a string the hub did not write itself.
 *
 * <p>{@code text} and {@code varchar} refuse U+0000 ({@code 22021 invalid byte sequence for
 * encoding "UTF8": 0x00}). Text that arrives from outside can carry one — a webhook endpoint
 * answering a binary body, decoded as a string — and a failed INSERT on a shared transaction
 * costs far more than its own row: on the outbox sweep it rolled back the whole batch, cursor
 * included, for every tenant (#943).
 */
public final class PgText {

    private PgText() {}

    /**
     * {@code s} with every U+0000 replaced by U+FFFD; {@code null} stays {@code null}.
     *
     * <p>Replaced rather than stripped: U+FFFD is what the JDK decoder already writes for every
     * byte it cannot represent, so a NUL reads like its undecodable neighbours instead of
     * vanishing — the stored text never claims the remote side sent {@code xy} when it sent
     * {@code x\0y}.
     */
    public static String storable(String s) {
        return s == null ? null : s.replace('\u0000', '\uFFFD');
    }

    /**
     * Whether {@code value} holds a U+0000 anywhere: in a string, in a map's keys or values, in a collection's elements.
     * What a {@code jsonb} column would refuse, asked of the item before it is serialized.
     */
    public static boolean holdsNul(Object value) {
        if (value instanceof CharSequence text) return text.toString().indexOf('\u0000') >= 0;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) if (holdsNul(e.getKey()) || holdsNul(e.getValue())) return true;
        } else if (value instanceof Iterable<?> elements) {
            for (Object element : elements) if (holdsNul(element)) return true;
        }
        return false;
    }

    /**
     * {@code tree} with every U+0000, in its text values and in its field names, replaced as
     * {@link #storable(String)} does, for a {@code jsonb} column, which refuses the character too. A copy:
     * the argument is left as it was.
     */
    public static JsonNode storableJson(JsonNode tree) {
        if (tree == null) return null;
        if (tree.isTextual()) return TextNode.valueOf(storable(tree.textValue()));
        if (tree instanceof ObjectNode o) {
            ObjectNode copy = o.objectNode();
            Map<String, Integer> taken = new HashMap<>();   // per sanitised name: how many alike were renamed so far
            o.properties().forEach(e -> {
                String name = storable(e.getKey());
                if (copy.has(name)) {   // two names that sanitise alike must not overwrite each other
                    String base = name;
                    do name = base + "\uFFFD#" + taken.merge(base, 1, Integer::sum); while (copy.has(name));
                }
                copy.set(name, storableJson(e.getValue()));
            });
            return copy;
        }
        if (tree instanceof ArrayNode a) {
            ArrayNode copy = a.arrayNode();
            a.forEach(v -> copy.add(storableJson(v)));
            return copy;
        }
        return tree;
    }
}

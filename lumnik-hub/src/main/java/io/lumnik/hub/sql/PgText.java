package io.lumnik.hub.sql;

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
}

package io.lumnik.hub.search.mark;

/**
 * Mirrors {@code core.search_projection_mark.mark_kind}'s {@code CHECK (mark_kind IN ('KEY', 'ALL'))}
 * (V56, design §3). {@link #fromSql(String)} throws loudly on an unknown value — that must never
 * happen in practice (the DB constraint already refuses it), so a mismatch here means the enum and
 * the constraint have drifted, and staying silent would be worse than failing loud.
 */
public enum MarkKind {
    KEY, ALL;

    public String sqlValue() {
        return name();
    }

    public static MarkKind fromSql(String value) {
        for (MarkKind kind : values()) {
            if (kind.name().equals(value)) return kind;
        }
        throw new IllegalArgumentException("Unknown mark_kind: " + value);
    }
}

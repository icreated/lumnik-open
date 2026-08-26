package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The honesty guard that lets the SQL-gen model say "I can't answer that" and honors it — abstention over
 * confident-wrong. Two triggers: a deterministic empty-schema floor (nothing to be faithful to), and the
 * model's explicit {@link #SENTINEL} exit. Pure; the wiring lives in {@code AskToSqlAgent.ask}.
 */
@ApplicationScoped
public class AbstentionGuard {

    /** The token the model emits when it cannot answer from the declared schema (the gen prompt names it). */
    public static final String SENTINEL = "NO_ANSWER";

    public static final String NO_SCHEMA_MESSAGE =
        "I have no data declared for this scope yet, so I can't answer that. Ingest a source for this scope first.";
    public static final String CANNOT_ANSWER_MESSAGE =
        "I can't answer that from the data available for this scope — the question doesn't map to the declared schema.";

    /** True when the card has nothing to be faithful to: null, no tables, or no columns on any table. */
    public boolean hasNoSchema(SchemaCard card) {
        return card == null || card.tables().isEmpty()
            || card.tables().stream().allMatch(t -> t.columns() == null || t.columns().isEmpty());
    }

    /** True when the model took its honest exit — the whole trimmed output IS the sentinel (case-insensitive),
     *  so a real SQL that merely mentions the token never trips it. */
    public boolean isAbstention(String modelOutput) {
        return modelOutput != null && modelOutput.trim().equalsIgnoreCase(SENTINEL);
    }
}

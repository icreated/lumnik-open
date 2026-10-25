package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityViewCompiler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * The honesty guard that lets the SQL-gen model say "I can't answer that" and honors it — abstention over
 * confident-wrong. Two triggers: a deterministic empty-schema floor (nothing to be faithful to), and the
 * model's explicit {@link #SENTINEL} exit. Pure; the wiring lives in {@code AskToSqlAgent.ask}.
 */
@ApplicationScoped
public class AbstentionGuard {

    /** The token the model emits when it cannot answer from the declared schema (the gen prompt names it). */
    public static final String SENTINEL = "NO_ANSWER";

    /** The English sentences — what an absent or unsupported {@code lang} gets; {@link AskSentences} holds both languages (#915). */
    public static final String NO_SCHEMA_MESSAGE = AskSentences.noSchema(null);
    public static final String CANNOT_ANSWER_MESSAGE = AskSentences.cannotAnswer(null);

    /**
     * What the scope holds that this door structurally cannot read. Appended to
     * {@link #CANNOT_ANSWER_MESSAGE} so a business user is told the answer EXISTS, not merely that SQL
     * could not find it.
     *
     * <p><b>Facts, not gestures.</b> An earlier draft named {@code lm chat} and
     * {@code lm conflicts list}. This prose is served verbatim to every client: the PWA renders
     * {@code answer} as plain text in its amber refusal bubble ({@code chat.mjs}), and it posts
     * {@code mode: 'analytic'} with no toggle — so a business user on a phone would have been sent, in
     * backticks, to two commands they have no terminal for and one door their build does not expose.
     * Naming the fact keeps the sentence true on both surfaces; each client already has its own
     * gesture for it (the PWA shows a fused record's conflicts on its detail view, {@code lm} has
     * {@code lm conflicts list}). The structured half that lets each client render its own wording
     * now rides beside the prose, as {@link Abstention#otherDoors()} (#515); this sentence stays
     * for every client that knows nothing about those tokens.
     */
    static final String LIFECYCLE_SIGNPOST = AskSentences.lifecycleSignpost(null);

    /** @see #LIFECYCLE_SIGNPOST */
    static final String CONFLICTS_SIGNPOST = AskSentences.conflictsSignpost(null);

    /**
     * The abstention, plus what else this scope holds.
     *
     * <p>Two questions abstain for the same structural reason — the card advertises an entity's
     * canonical columns and nothing else — and for both, something one step away holds the answer.
     * A declared lifecycle lives in {@code rag.chunk} and text-to-SQL never reads it (#494); the
     * fusion ledger is a jsonb {@code _conflicts} column the card does not advertise (#495).
     *
     * <p>Arbitrated 2026-09-13: the abstention stays the answer in both cases. Teaching the analytic
     * door to read lifecycle chunks would have the SQL generator reasoning over non-SQL text, and
     * advertising {@code _conflicts} on a card that types every column {@code text} would have the
     * model writing {@code _conflicts->>…} against a column declared as text — the confident-wrong
     * the five guards exist to prevent, once in each direction. What was missing was not an answer.
     *
     * <p>A scope with nothing to point at gets the bare sentence it always got. The abstention is
     * recognised by {@code AskResult.abstained}, not by these words, which follow {@code lang} (#915).
     *
     * <p>Both conditions are PASSED, neither derived here. One derived and one passed is the
     * signature saying it has not decided whose question this is; {@link #hasAFusedEntity} stays
     * public so the caller can ask it — and so it can be tested — but the rendering stays pure.
     */
    public Abstention cannotAnswer(String lang, boolean scopeDeclaresALifecycle, boolean scopeHasAFusedEntity) {
        return signpost(AskSentences.cannotAnswer(lang), lang, scopeDeclaresALifecycle, scopeHasAFusedEntity);
    }

    /**
     * {@link #cannotAnswer} generalised to any dead-end sentence: the SqlGuard-rejection and
     * {@code ZeroRowGuard.CAVEAT} refusals reach the same two structural reasons the model's own
     * {@link #SENTINEL} exit does — the schema has nothing to answer from, or nothing runnable came
     * of it — and the doors are just as true there (#527). Gaining a door does not make the other two
     * dead ends into an abstention. {@code lang} picks the language of the signposts, which the caller
     * already chose for {@code baseMessage}.
     */
    public Abstention signpost(String baseMessage, String lang, boolean scopeDeclaresALifecycle,
                               boolean scopeHasAFusedEntity) {
        StringBuilder answer = new StringBuilder(baseMessage);
        List<String> doors = new ArrayList<>(2);
        if (scopeDeclaresALifecycle) { answer.append(AskSentences.lifecycleSignpost(lang)); doors.add(DOOR_LIFECYCLE); }
        if (scopeHasAFusedEntity)    { answer.append(AskSentences.conflictsSignpost(lang)); doors.add(DOOR_CONFLICTS); }
        return new Abstention(answer.toString(), List.copyOf(doors));
    }

    /**
     * A signposted dead end in the two shapes its readers need — {@link AskResult} says why there
     * are two. Not only the model's own abstention: SqlGuard rejection and {@code
     * ZeroRowGuard.CAVEAT} reach {@link #signpost} directly, so "abstention" here names the shape
     * of the refusal, not only the {@link #SENTINEL} exit.
     *
     * <p>ONE method builds both, appending the signpost and adding its token in the same branch:
     * the shape that makes them unable to drift. Two methods reading the same two booleans would
     * let a third signpost be written into the prose and forgotten in the tokens with nothing
     * going red — the sentence would still be correct, and a client would simply stop being
     * offered the gesture.
     */
    public record Abstention(String answer, List<String> otherDoors) {}

    /** {@link #LIFECYCLE_SIGNPOST}'s token. The vocabulary is {@link AskResult}'s, not this class's. */
    static final String DOOR_LIFECYCLE = AskResult.DOOR_LIFECYCLE;

    /** {@link #CONFLICTS_SIGNPOST}'s token. */
    static final String DOOR_CONFLICTS = AskResult.DOOR_CONFLICTS;

    /**
     * Does this card advertise a fused entity view? The signal is the {@code connector.v_} prefix
     * {@code EntityViewCompiler} writes, not the letter v — a connector table named
     * {@code t_v_customer} fuses nothing and has no ledger, and a signpost to a dead end is worse
     * than none.
     */
    public boolean hasAFusedEntity(SchemaCard card) {
        return card != null && card.tables().stream()
            .anyMatch(t -> EntityViewCompiler.entityNameFromView(t.name()) != null);
    }

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

package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.llm.spi.Judge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * Scores whether a generated SQL faithfully answers the question, using an INDEPENDENT judge model
 * ({@link Judge}). Offline/eval only — never in the request path. A probabilistic measurement, so a
 * garbled/blank/failed judge reply is {@code INCONCLUSIVE} (the eval abstains rather than scoring noise).
 *
 * <p>The judge model is <em>optional</em>. The open edition ships without lumnik-llm-langchain, so no
 * {@code @Judge ChatPort} exists there at all; an absent judge abstains exactly like a crashed one,
 * which is the same doctrine one step earlier. Injecting the port directly instead would fail Arc
 * validation at build time and stop the whole hub from starting over an offline eval tool.
 */
@ApplicationScoped
public class FaithfulnessJudge {

    public enum Verdict { FAITHFUL, UNFAITHFUL, INCONCLUSIVE }
    public record Result(Verdict verdict, String reason) {}

    private final Instance<ChatPort> judges;

    @Inject
    FaithfulnessJudge(@Judge Instance<ChatPort> judges) { this.judges = judges; }

    public Result assess(String question, String sql, SchemaCard card) {
        if (judges.isUnsatisfied())
            return new Result(Verdict.INCONCLUSIVE, "no judge model available");
        ChatPort judge = judges.get();
        String prompt = """
            You are a strict SQL reviewer. Decide whether the SQL faithfully answers the question using
            ONLY the tables and columns listed below (text after -- is the business meaning).

            Schema:
            %s
            Question: %s
            Generated SQL: %s

            A trailing row-count cap (e.g. `LIMIT 500`) is added automatically by the system as a safety
            bound — it does NOT make the query unfaithful, so ignore it and judge the query's logic.

            IMPORTANT — check the JOIN type FIRST. If the question asks to list or count EACH / EVERY / ALL of
            some entity together with a related table (e.g. "each customer and how many orders they have"),
            then every such entity must still appear even when it has zero related rows. A plain JOIN or an
            INNER JOIN drops the entities with zero matches, so it is UNFAITHFUL — only a LEFT JOIN is
            faithful here. (An INNER JOIN is faithful ONLY when the question explicitly wants just the
            entities that HAVE a related row, e.g. "which customers have an order".)

            Does the SQL measure the right thing, filter as asked, join only what is needed, and invent no
            table/column/value? Reply on the FIRST line with EXACTLY one of:
            FAITHFUL
            UNFAITHFUL: <one-line reason>
            INCONCLUSIVE: <one-line reason>   (only if the question or schema is too ambiguous to judge)
            Nothing else.
            """.formatted(card.toPrompt(), question, sql);

        String reply;
        try {
            reply = judge.chat(prompt);
        } catch (RuntimeException e) {
            return new Result(Verdict.INCONCLUSIVE, "judge call failed: " + e.getMessage());
        }
        if (reply == null || reply.isBlank()) return new Result(Verdict.INCONCLUSIVE, "empty judge reply");

        // The verdict is on the FIRST non-empty line (per the rubric); parse only that line, so a word in a
        // later-line explanation can't flip the verdict and the stored reason stays bounded.
        String line = reply.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        String up = line.toUpperCase(Locale.ROOT);
        if (up.contains("UNFAITHFUL")) // check UNFAITHFUL first — it contains "FAITHFUL"
            return new Result(Verdict.UNFAITHFUL, reasonAfter(line, up, "UNFAITHFUL"));
        if (up.contains("INCONCLUSIVE")) // the judge abstained — keep its reason, not the token
            return new Result(Verdict.INCONCLUSIVE, reasonAfter(line, up, "INCONCLUSIVE"));
        if (up.contains("FAITHFUL")) return new Result(Verdict.FAITHFUL, "");
        return new Result(Verdict.INCONCLUSIVE, line); // unrecognized first line — the line is the reason
    }

    /** The one-line reason after the colon that follows {@code token} (empty if none). */
    private static String reasonAfter(String line, String upper, String token) {
        int colon = line.indexOf(':', upper.indexOf(token));
        return colon >= 0 ? line.substring(colon + 1).trim() : "";
    }
}

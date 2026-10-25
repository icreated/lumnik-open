package io.lumnik.extraction.spi.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of an analytic ask: the answer, the SQL that ran, the rows, and their count.
 *
 * <p>{@code dataAsOf}: ISO-8601 end of the scope's last completed ingestion run — how fresh the
 * answer's ground is. Null on refusals, and when the scope has no completed run.
 *
 * <p>{@code otherDoors}: when this door reaches a signposted dead end — not only the model's own
 * abstention, but any refusal for which the scope holds the answer somewhere text-to-SQL
 * structurally cannot read (#527) — the tokens naming those doors — {@code "lifecycle"},
 * {@code "conflicts"}. Empty otherwise, and empty is the common case.
 *
 * <p>The dead end's own sentence already states the FACT, and deliberately names no command:
 * the same string is served to {@code lm ask}, to the PWA and to anything else on the API, so a
 * backtick-quoted CLI command would send a business user on a phone to a terminal they do not have. The
 * consequence was that the client which COULD act had to be told in prose it cannot parse — which
 * is exactly the string-matching coupling #512 removed from the OTK grader. These tokens are the
 * structured half: each client maps them to its own gesture, or to nothing when it has none
 * ({@code lm} offers {@code lm chat} and {@code lm conflicts list}; the PWA offers its fiches, and
 * has no lifecycle door to offer at all). The prose stays for every client that knows neither.
 *
 * <p>{@code disputed} (#874): the fused records this answer filtered on while their sources
 * disagree about the filtered column — at most ten, excluded ones first; {@code disputedTruncated}
 * says some were cut. Empty in the common case. When it is not empty, {@code otherDoors} carries
 * {@link #DOOR_CONFLICTS} — {@code otherDoors} no longer names dead ends only.
 *
 * <p>{@code abstained} (#915): the door declined to answer — the model's own {@code NO_ANSWER} exit, a
 * scope with no schema to answer from, or a guard refusal (rejected SQL, an undeclared table, column or
 * value, an ambiguous filter, a refused read, a zero-row result, a figure the rows do not back). It is
 * the structural mark of a refusal: the sentence is served in the language the caller named, so no client
 * or grader may recognise it by what its words say. False for an answer.
 *
 * <p>{@code understoodAs} (#1096): the question this result answers, when the asked one continued a previous
 * question and was resolved against it — the surface sends it back as the next {@code previous}. Null for a
 * standalone question, and for a follow-up that could not be resolved.
 */
public record AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                        String dataAsOf, List<String> otherDoors,
                        List<Disputed> disputed, boolean disputedTruncated, boolean abstained,
                        String understoodAs) {

    /** The scope declares a document lifecycle; the semantic door retrieves it, SQL never reads it. */
    public static final String DOOR_LIFECYCLE = "lifecycle";

    /** The scope fuses an entity, so source disagreements are recorded row by row in a ledger. */
    public static final String DOOR_CONFLICTS = "conflicts";

    /**
     * Every token this SPI defines. The vocabulary lives HERE, on the record that carries it,
     * rather than in the hub implementation that happens to decide when to send one: it is a
     * contract three languages read, and an unknown token is silence by design — so a renamed
     * constant would cost both clients their gesture with every Java test still green.
     */
    public static final List<String> DOORS = List.of(DOOR_LIFECYCLE, DOOR_CONFLICTS);

    public AskResult {
        otherDoors = otherDoors == null ? List.of() : List.copyOf(otherDoors);
        disputed = disputed == null ? List.of() : List.copyOf(disputed);
        // No client renders an empty list, so a lone truncation flag would say nothing visible.
        disputedTruncated = disputedTruncated && !disputed.isEmpty();
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                     String dataAsOf, List<String> otherDoors,
                     List<Disputed> disputed, boolean disputedTruncated, boolean abstained) {
        this(answer, sql, rows, rowCount, dataAsOf, otherDoors, disputed, disputedTruncated, abstained, null);
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                     String dataAsOf, List<String> otherDoors,
                     List<Disputed> disputed, boolean disputedTruncated) {
        this(answer, sql, rows, rowCount, dataAsOf, otherDoors, disputed, disputedTruncated, false);
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                     String dataAsOf, List<String> otherDoors) {
        this(answer, sql, rows, rowCount, dataAsOf, otherDoors, List.of(), false, false);
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                     String dataAsOf) {
        this(answer, sql, rows, rowCount, dataAsOf, List.of(), List.of(), false, false);
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount) {
        this(answer, sql, rows, rowCount, null, List.of(), List.of(), false, false);
    }

    /** This result with the disputed records attached; a non-empty list also names the conflicts door, once. */
    public AskResult withDisputed(List<Disputed> disputed, boolean truncated) {
        List<String> doors = otherDoors;
        if (disputed != null && !disputed.isEmpty() &&!doors.contains(DOOR_CONFLICTS)) {
            doors = new ArrayList<>(doors);
            doors.add(DOOR_CONFLICTS);
        }
        return new AskResult(answer, sql, rows, rowCount, dataAsOf, doors, disputed, truncated, abstained, understoodAs);
    }

    /** This result marked as an abstention (#915). */
    public AskResult withAbstained() {
        return new AskResult(answer, sql, rows, rowCount, dataAsOf, otherDoors, disputed, disputedTruncated, true, understoodAs);
    }

    /** This result, answering {@code understoodAs} (#1096). */
    public AskResult withUnderstoodAs(String understoodAs) {
        return new AskResult(answer, sql, rows, rowCount, dataAsOf, otherDoors, disputed, disputedTruncated, abstained, understoodAs);
    }
}

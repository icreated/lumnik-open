package io.lumnik.extraction.spi.ask;

import java.util.List;
import java.util.Map;

/**
 * Result of an analytic ask: the answer, the SQL that ran, the rows, and their count.
 *
 * <p>{@code dataAsOf}: ISO-8601 end of the scope's last completed ingestion run — how fresh the
 * answer's ground is. Null on refusals, and when the scope has no completed run.
 *
 * <p>{@code otherDoors}: when this door abstains and the scope holds the answer somewhere text-to-
 * SQL structurally cannot read, the tokens naming those doors — {@code "lifecycle"},
 * {@code "conflicts"}. Empty otherwise, and empty is the common case.
 *
 * <p>The abstention's own sentence already states the FACT, and deliberately names no command:
 * the same string is served to {@code lm ask}, to the PWA and to anything else on the API, so a
 * backtick-quoted CLI command would send a métier on a phone to a terminal they do not have. The
 * consequence was that the client which COULD act had to be told in prose it cannot parse — which
 * is exactly the string-matching coupling #512 removed from the OTK grader. These tokens are the
 * structured half: each client maps them to its own gesture, or to nothing when it has none
 * ({@code lm} offers {@code lm chat} and {@code lm conflicts list}; the PWA offers its fiches, and
 * has no lifecycle door to offer at all). The prose stays for every client that knows neither.
 */
public record AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                        String dataAsOf, List<String> otherDoors) {

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
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                     String dataAsOf) {
        this(answer, sql, rows, rowCount, dataAsOf, List.of());
    }

    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount) {
        this(answer, sql, rows, rowCount, null, List.of());
    }
}

package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.lumnik.hub.sql.SqlFailureText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a failure is read, and what the disclosure says — as tables.
 *
 * <p>The failure table's last row is what proves the cause walk is bounded at all: a violation
 * buried deeper than {@code MAX_CAUSE_DEPTH} must NOT be found, so an unbounded walk reddens it.
 * The cycle row then terminates as a consequence — which is why it can be asserted without a
 * mutation that would hang the build to prove itself.
 *
 * <p>The SQL side of the disclosure moved to {@code SqlFiltersTest} with the code it exercises.
 */
class AmbiguousFilterGuardTest {

    /** A cause chain that points at itself — the shallowest of the shapes the depth bound covers. */
    private static final class SelfCausing extends RuntimeException {
        @Override public synchronized Throwable getCause() { return this; }
    }

    private record Failure(String name, Throwable thrown, boolean ambiguous) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> failures() {
        SQLException cardinality = new SQLException("more than one row returned by a subquery "
            + "used as an expression", AmbiguousFilterGuard.SQLSTATE_CARDINALITY);
        return Stream.of(
            new Failure("the cardinality violation itself", cardinality, true),
            new Failure("wrapped, the way the agent rethrows it",
                new RuntimeException("AskToSqlAgent.runReadOnly", cardinality), true),
            new Failure("wrapped twice", new RuntimeException(new RuntimeException(cardinality)), true),
            new Failure("a permission failure is a different story",
                new SQLException("permission denied for table t", "42501"), false),
            new Failure("a SQL error carrying no state at all",
                new SQLException("something went wrong"), false),
            new Failure("nothing to do with SQL", new IllegalStateException("boom"), false),
            new Failure("no failure at all", null, false),
            new Failure("a self-referencing cause terminates rather than spinning",
                new SelfCausing(), false),
            new Failure("a two-step cause cycle terminates too — initCause only refuses self-causation",
                twoStepCycle(), false),
            new Failure("a cardinality violation buried below the walk's bound is simply not found",
                buried(cardinality, SqlFailureText.MAX_CAUSE_DEPTH + 5), false)
        ).map(Arguments::of);
    }

    /** A→B→A: legal in plain Java, and an unbounded cause walk never leaves it. */
    private static Throwable twoStepCycle() {
        class Link extends RuntimeException {
            private Throwable link;
            @Override public synchronized Throwable getCause() { return link; }
        }
        Link a = new Link();
        Link b = new Link();
        a.link = b;
        b.link = a;
        return a;
    }

    /** {@code failure} wrapped {@code depth} times over. */
    private static Throwable buried(Throwable failure, int depth) {
        Throwable t = failure;
        for (int i = 0; i < depth; i++) t = new RuntimeException("layer " + i, t);
        return t;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void only_a_cardinality_violation_is_treated_as_an_ambiguous_filter(Failure failure) {
        assertThat(AmbiguousFilterGuard.isAmbiguity(failure.thrown())).isEqualTo(failure.ambiguous());
    }

    private record Root(String name, Throwable thrown, String expected) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> roots() {
        return Stream.of(
            new Root("the deepest cause is the one that speaks",
                new RuntimeException("wrapper",
                    new SQLException("ERROR: permission denied for table t", "42501")),
                "ERROR: permission denied for table t"),
            new Root("only the first line — a Postgres error carries its Position on the next one",
                new SQLException("ERROR: relation \"t\" does not exist\n  Position: 20", "42P01"),
                "ERROR: relation \"t\" does not exist"),
            new Root("a causeless failure still names itself rather than saying nothing",
                new IllegalStateException(), "IllegalStateException."),
            new Root("no failure at all", null, "(no cause)"),

            // Structural classes speak: their messages name identifiers the caller already holds
            // in the SQL we hand back with the answer.
            new Root("21 — the cardinality violation that started all of this",
                new SQLException("ERROR: more than one row returned by a subquery used as an expression",
                    "21000"),
                "ERROR: more than one row returned by a subquery used as an expression"),
            new Root("42 — an undefined table names an identifier, not a value",
                new SQLException("ERROR: relation \"t\" does not exist", "42P01"),
                "ERROR: relation \"t\" does not exist"),
            new Root("42 — so does a permission failure",
                new SQLException("ERROR: permission denied for table t", "42501"),
                "ERROR: permission denied for table t"),
            new Root("57 — our own statement timeout is operational",
                new SQLException("ERROR: canceling statement due to statement timeout", "57014"),
                "ERROR: canceling statement due to statement timeout"),

            // Class 22 is "data exception" by definition, and the message proves it.
            new Root("22 — the message carries a STORED VALUE, so it is withheld",
                new SQLException("ERROR: invalid input syntax for type bigint: \"secret-value\"", "22P02"),
                "SQLSTATE 22P02 (the message is withheld — that class can carry stored values; "
                    + "the server log has it)."),
            new Root("an unreasoned class is withheld too — the rule decides the codes nobody imagined",
                new SQLException("ERROR: something about a row", "23505"),
                "SQLSTATE 23505 (the message is withheld — that class can carry stored values; "
                    + "the server log has it)."),
            new Root("a SQLException with no state at all is withheld",
                new SQLException("ERROR: mystery"),
                "SQLSTATE unknown (the message is withheld — that class can carry stored values; "
                    + "the server log has it).")
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roots")
    void the_root_message_is_the_databases_words_and_the_walk_always_ends(Root root) {
        assertThat(SqlFailureText.rootMessage(root.thrown())).isEqualTo(root.expected());
    }

    @Test
    void the_disclosure_names_every_match_and_the_gesture_that_resolves_it() {
        String m = AmbiguousFilterGuard.message("c_bpartner_id", List.of("1000123", "1000456"), false);

        assertThat(m).contains("2 rows")
            .contains("1000123", "1000456")
            .as("the point of the message is the next question, not the apology")
            .contains("Ask again");
    }

    @Test
    void the_same_id_twice_is_listed_once_while_the_row_count_stays_honest() {
        // Two partner rows carrying ONE id: the query still failed on two rows, but repeating the
        // id would offer a choice that is not one. The count is what the database saw.
        String m = AmbiguousFilterGuard.message("c_bpartner_id", List.of("1000123", "1000123"), false);

        assertThat(m).contains("matches 2 rows")
            .as("a repeated id is not a choice the asker can make")
            .containsOnlyOnce("1000123");
    }

    @Test
    void a_capped_probe_says_at_least_rather_than_presenting_a_ceiling_as_a_total() {
        String m = AmbiguousFilterGuard.message("c_bpartner_id", List.of("a", "b"), true);

        assertThat(m).as("SqlGuard caps the probe; a ceiling stated as a total is confident-wrong")
            .contains("at least 2 rows");
    }

    @Test
    void a_long_duplicate_set_is_summarised_rather_than_dumped() {
        List<String> many = IntStream.range(0, AmbiguousFilterGuard.MAX_LISTED + 5)
            .mapToObj(i -> "id-" + i).toList();

        String m = AmbiguousFilterGuard.message("c_bpartner_id", many, false);

        assertThat(m).contains("id-0")
            .contains("and 5 more")
            .as("naming every one of a long set would bury the gesture it is there to enable")
            .doesNotContain("id-" + (AmbiguousFilterGuard.MAX_LISTED + 1));
    }
}

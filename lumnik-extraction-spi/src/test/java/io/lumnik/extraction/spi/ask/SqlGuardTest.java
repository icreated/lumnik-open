package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard's contract as three tables: what it must refuse, what it must NOT refuse, and what the
 * injected bound must actually be worth.
 *
 * <p>{@code @CsvSource} is unusable here — JUnit's default quote character is the apostrophe, which
 * is exactly the SQL string delimiter — so every table is a {@code @MethodSource}. The first column
 * is the rule, restated in the assertion so a failing row names itself.
 */
class SqlGuardTest {

    private static final int MAX = 500;
    private final SqlGuard guard = new SqlGuard(MAX);

    // ── what must still be refused ──────────────────────────────────────────

    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of("a DML statement", "UPDATE c_order SET grandtotal=0", "only SELECT/WITH allowed"),
            Arguments.of("two statements", "SELECT 1 FROM c_order; DROP TABLE c_order",
                         "multiple statements not allowed"),
            Arguments.of("a sleep probe", "SELECT pg_sleep(10)", "banned token in SQL"),
            Arguments.of("a catalog read", "SELECT * FROM pg_catalog.pg_user", "banned token in SQL"),
            Arguments.of("a server-side COPY", "COPY c_order TO '/tmp/x'", "only SELECT/WITH allowed"),
            Arguments.of("a banned token behind a leading comment",
                         "-- innocent\nDROP TABLE c_order", "only SELECT/WITH allowed"),
            Arguments.of("nothing at all", "   ", "empty SQL"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @MethodSource("refusals")
    void refusesUnsafeSql(String rule, String sql, String expectedMessage) {
        assertThatThrownBy(() -> guard.check(sql))
            .as("must refuse %s", rule)
            .isInstanceOf(SqlGuard.SqlRejectedException.class)
            .hasMessageContaining(expectedMessage);
    }

    // ── what must NOT be refused ────────────────────────────────────────────
    // Over-blocking a legitimate query is the cardinal harm: the user is told their honest question
    // is unsafe. Every row here is ordinary business data or ordinary model output.

    static Stream<Arguments> acceptances() {
        return Stream.of(
            Arguments.of("a plain projection", "SELECT documentno FROM c_order"),
            Arguments.of("a CTE", "WITH x AS (SELECT 1) SELECT * FROM x"),
            Arguments.of("a customer living in a town called Grant",
                         "SELECT * FROM c_order WHERE city = 'Grant'"),
            Arguments.of("a note containing the word update",
                         "SELECT * FROM c_order WHERE note LIKE '%update%'"),
            Arguments.of("a reference containing a semicolon",
                         "SELECT * FROM c_order WHERE ref = 'A;B'"),
            Arguments.of("a model that prefixed its answer with a line comment",
                         "-- orders by customer\nSELECT documentno FROM c_order"),
            Arguments.of("a model that prefixed its answer with a block comment",
                         "/* orders by customer */ SELECT documentno FROM c_order"),
            Arguments.of("a column quoted because it collides with a keyword",
                         "SELECT \"insert\" FROM c_order"));
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("acceptances")
    void doesNotOverBlockLegitimateSql(String rule, String sql) {
        assertThat(guard.check(sql)).as("must not refuse %s", rule).isNotBlank();
    }

    // ── the bound must be worth something ───────────────────────────────────

    static Stream<Arguments> bounds() {
        return Stream.of(
            Arguments.of("a bare SELECT is bounded", "SELECT 1 FROM c_order", MAX),
            Arguments.of("a smaller existing bound is kept", "SELECT 1 FROM c_order LIMIT 10", 10),
            Arguments.of("a larger existing bound is capped", "SELECT 1 FROM c_order LIMIT 100000", MAX),
            Arguments.of("a bound that overflows an int is capped, not a crash",
                         "SELECT 1 FROM c_order LIMIT 99999999999999", MAX),
            Arguments.of("an OFFSET tail does not earn a second LIMIT",
                         "SELECT 1 FROM c_order LIMIT 10 OFFSET 20", 10),
            Arguments.of("a trailing line comment cannot swallow the bound",
                         "SELECT 1 FROM c_order -- every row", MAX),
            Arguments.of("a trailing block comment cannot swallow the bound",
                         "SELECT 1 FROM c_order /* every row */", MAX));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bounds")
    void theEmittedSqlCarriesAnEffectiveBound(String rule, String sql, int expectedLimit) {
        String out = guard.check(sql);

        assertThat(effectiveText(out))
            .as("%s — the LIMIT must survive comment removal, exactly once", rule)
            .containsIgnoringCase("limit " + expectedLimit);
        assertThat(countIgnoringCase(effectiveText(out), "limit"))
            .as("%s — exactly one LIMIT, or the SQL does not parse", rule)
            .isEqualTo(1);
    }

    // ── the bound must bind the OUTERMOST query ─────────────────────────────
    // A LIMIT the guard can see is not the same as a LIMIT that bounds the answer. Accepting one
    // from a CTE or a subquery let the outer query run unbounded — the very hole this guard closes.

    static Stream<Arguments> nestedBounds() {
        return Stream.of(
            Arguments.of("a LIMIT inside a CTE",
                         "WITH x AS (SELECT 1 LIMIT 1) SELECT * FROM c_order"),
            Arguments.of("a LIMIT inside an IN-subquery",
                         "SELECT * FROM c_order WHERE id IN (SELECT id FROM c_line ORDER BY d LIMIT 10)"),
            Arguments.of("a LIMIT inside a scalar subquery in the SELECT list",
                         "SELECT (SELECT x FROM c_line LIMIT 1) AS first FROM c_order"),
            Arguments.of("a LIMIT inside a derived table",
                         "SELECT * FROM (SELECT * FROM c_line LIMIT 3) z"));
    }

    @ParameterizedTest(name = "{0} does not count as the answer's bound")
    @MethodSource("nestedBounds")
    void aBoundInANestedScopeDoesNotSatisfyTheGuard(String rule, String sql) {
        assertThat(effectiveText(guard.check(sql)))
            .as("%s — the OUTER query is still unbounded, so the guard must add its own", rule)
            .containsIgnoringCase("limit " + MAX);
    }

    // ── the lexer must not disagree with Postgres in the unsafe direction ────
    // Every row is a shape where a naive lexer ends a string or a comment somewhere Postgres does
    // not, hiding a statement separator or a verb behind what the guard reads as inert text.

    static Stream<Arguments> lexerDisagreements() {
        return Stream.of(
            Arguments.of("a dollar-quoted apostrophe hiding a second statement",
                         "SELECT $$'$$ ; SET app.current_tenant TO 0"),
            Arguments.of("a dollar-quoted body at all",
                         "SELECT $$x$$ FROM c_order"),
            Arguments.of("a NESTED block comment hiding a second statement",
                         "SELECT 1 /* /* */ ' */ ; SELECT 2"),
            Arguments.of("a nested block comment hiding a non-SELECT verb",
                         "/* /* */ SELECT 1 LIMIT 1 */ SHOW ALL"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @MethodSource("lexerDisagreements")
    void refusesSqlWhoseMeaningDependsOnWhereTheCommentOrLiteralEnds(String rule, String sql) {
        assertThatThrownBy(() -> guard.check(sql))
            .as("%s — Postgres and the lexer read this differently, so it must not run", rule)
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    // ── quoting hides a verb, never an object the denylist names ────────────

    static Stream<Arguments> quotedIdentifiers() {
        return Stream.of(
            Arguments.of("a column quoted because it collides with a verb", "SELECT \"insert\" FROM c_order", false),
            Arguments.of("a column named update", "SELECT \"update\" FROM c_order", false),
            Arguments.of("the catalogue schema, quoted", "SELECT * FROM \"pg_catalog\".\"pg_tables\"", true),
            Arguments.of("information_schema, quoted", "SELECT * FROM \"information_schema\".\"columns\"", true),
            Arguments.of("a sleep probe, quoted", "SELECT \"pg_sleep\"(10)", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotedIdentifiers")
    void quotingExemptsAVerbButNeverANamedObject(String rule, String sql, boolean refused) {
        if (refused) {
            assertThatThrownBy(() -> guard.check(sql))
                .as("%s — quoting names the very object the denylist exists to deny", rule)
                .isInstanceOf(SqlGuard.SqlRejectedException.class);
        } else {
            assertThat(guard.check(sql)).as("%s — a quoted token is a name, not a verb", rule).isNotBlank();
        }
    }

    // ── the refusal is the only way out ─────────────────────────────────────

    static Stream<Arguments> degenerateBounds() {
        return Stream.of(
            Arguments.of("a fractional bound", "SELECT 1 FROM c_order LIMIT 1.5"),
            Arguments.of("a bound that is only a dot", "SELECT 1 FROM c_order LIMIT ."),
            Arguments.of("a bound wider than a long", "SELECT 1 FROM c_order LIMIT 999999999999999999999"),
            Arguments.of("a bound with no value at all", "SELECT 1 FROM c_order LIMIT"),
            Arguments.of("a word where the bound should be", "SELECT 1 FROM c_order LIMIT all"));
    }

    @ParameterizedTest(name = "{0} leaves through the refusal, never as a raw crash")
    @MethodSource("degenerateBounds")
    void aDegenerateBoundNeverEscapesAsAnUncheckedCrash(String rule, String sql) {
        try {
            guard.check(sql);
        } catch (SqlGuard.SqlRejectedException expected) {
            // the honest exit: the caller turns this into an explained refusal
        } catch (RuntimeException wrong) {
            fail("%s — escaped as %s, so the user sees a 500 instead of a refusal"
                     .formatted(rule, wrong.getClass().getSimpleName()));
        }
    }

    // ── housekeeping the tables do not cover ────────────────────────────────

    @Test
    void stripsCodeFencesAndTheTrailingSemicolon() {
        String out = guard.check("```sql\nSELECT 1 FROM c_order;\n```");
        assertThat(out).startsWith("SELECT 1 FROM c_order");
        assertThat(out).doesNotContain("`").doesNotContain(";");
    }

    /**
     * An oracle independent of the guard: what Postgres would still see after the comments are gone.
     * Deliberately naive — no row in these tables puts {@code --} inside a string literal.
     */
    private static String effectiveText(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\n]*", " ");
    }

    private static int countIgnoringCase(String haystack, String needle) {
        int count = 0;
        int at = 0;
        String h = haystack.toLowerCase();
        while ((at = h.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }
}

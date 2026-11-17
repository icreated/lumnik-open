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
            // $ continues a Postgres identifier (measured: a$b, created quoted, resolves unquoted), so
            // this is a column name — not the dollar-quoted body the guard refuses one table below.
            Arguments.of("a column name carrying a dollar sign", "SELECT a$b FROM c_order"),
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

    /**
     * A character Java calls whitespace and the server does not cannot be used to hide the bound.
     * {@code LIMIT<U+3000>1000000} lexes as ONE identifier now — which is what the server reads too, so
     * the statement is refused either way — and the guard must still emit a bound of its own rather
     * than treat the fragment as an existing one.
     */
    @Test
    void anExoticSpaceCannotSuppressTheBound() {
        assertThat(effectiveText(guard.check("SELECT x FROM c_order LIMIT\u30001000000")))
            .as("a bound the guard cannot read is not a bound it may trust")
            .containsIgnoringCase("limit " + MAX);
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
            Arguments.of("a line comment ended by a carriage return, hiding a second statement",
                         "SELECT 1 FROM c_order --x\r; DROP TABLE c_order"),
            Arguments.of("a line comment ended by a carriage return, hiding a banned call",
                         "SELECT 1 FROM c_order --x\r, set_config('app.current_tenant','11',true)"),
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

    // ── a literal form the lexer does not read (#1988) ──────────────────────
    // The two audit statements ran as lumnik_readonly on PostgreSQL 16 and read tables no card declares.

    static Stream<Arguments> prefixedLiterals() {
        return Stream.of(
            Arguments.of("an E string hiding a FROM (the audit)",
                         "SELECT E'\\'' AS q, count(*) AS n FROM connector.connector x -- '\nLIMIT 500"),
            Arguments.of("an e string, lower case", "SELECT e'\\'' AS q FROM connector.connector x -- '"),
            Arguments.of("an E string after a space", "SELECT E '\\'' AS q FROM connector.connector x -- '"),
            Arguments.of("a U& identifier spelling a schema (the audit)",
                         "SELECT count(*) AS n FROM U&\"c\\006fnnector\".connector_dead_letter\nLIMIT 500"),
            Arguments.of("a u& identifier, lower case", "SELECT count(*) FROM u&\"c\\006fnnector\".connector_dead_letter"),
            Arguments.of("a U& string", "SELECT U&'\\0041' FROM c_order"),
            Arguments.of("a U& string after a space", "SELECT U& '\\0041' FROM c_order"),
            Arguments.of("UESCAPE", "SELECT U&\"d!0061ta\" UESCAPE '!' FROM c_order"),
            Arguments.of("an E string whose backslash hides a second statement",
                         "SELECT E'\\'' ; SELECT 1 -- '"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @MethodSource("prefixedLiterals")
    void refusesALiteralFormTheLexerDoesNotRead(String rule, String sql) {
        assertThatThrownBy(() -> guard.check(sql))
            .as("%s — the lexer and the server end this literal in different places", rule)
            .isInstanceOf(SqlGuard.SqlRejectedException.class)
            .hasMessageContaining("literal form the guard does not read");
    }

    static Stream<Arguments> plainLiterals() {
        return Stream.of(
            Arguments.of("a value that is the letter E", "SELECT * FROM c_order WHERE grade = 'E'"),
            Arguments.of("a column named n compared to a string", "SELECT n FROM c_order WHERE n = 'x'"),
            Arguments.of("an alias named e then a string", "SELECT e.name, 'x' AS k FROM c_order e"),
            Arguments.of("a CASE whose branches are strings", "SELECT CASE WHEN a = 1 THEN 'x' ELSE 'b' END FROM c_order"),
            Arguments.of("a typed DATE literal", "SELECT * FROM c_order WHERE d > DATE '2024-01-01'"),
            Arguments.of("an INTERVAL literal", "SELECT now() - INTERVAL '1 day' FROM c_order"),
            Arguments.of("a quoted column named E", "SELECT \"E\" FROM c_order"),
            // The readonly transaction pins standard_conforming_strings=on (TenantRlsScope): a backslash in a plain
            // string is data to the server, which is how the lexer reads it.
            Arguments.of("a LIKE escaping its percent sign", "SELECT * FROM c_order WHERE rate LIKE '50\\%'"),
            Arguments.of("a regex on digits", "SELECT * FROM c_order WHERE code ~ '^\\d+'"),
            Arguments.of("a Windows path", "SELECT * FROM c_order WHERE path ILIKE '%C:\\data%'"),
            Arguments.of("a bit string, read alike by both", "SELECT B'101' FROM c_order"),
            Arguments.of("a hex string, read alike by both", "SELECT x'1F' FROM c_order"),
            Arguments.of("a national string, read alike by both", "SELECT N'abc' FROM c_order"));
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("plainLiterals")
    void aPlainLiteralIsAccepted(String rule, String sql) {
        assertThat(guard.check(sql)).as("must not refuse %s", rule).isNotBlank();
    }

    // ── a function that moves the boundary or runs SQL from a string (#1987) ─

    static Stream<Arguments> boundaryFunctions() {
        return Stream.of(
            Arguments.of("set_config in a materialised CTE (the audit)",
                         "WITH s AS MATERIALIZED (SELECT set_config('app.current_tenant','11',true) AS x) "
                             + "SELECT count(*) AS n FROM s, connector.t_zombie_workorder_p1 w WHERE s.x IS NOT NULL"),
            Arguments.of("set_config in a scalar subquery",
                         "SELECT count(*) FROM connector.t_x WHERE (SELECT set_config('app.current_tenant','11',true)) IS NOT NULL"),
            Arguments.of("set_config, quoted", "SELECT \"set_config\"('app.current_tenant','11',true)"),
            Arguments.of("set_config, upper case", "SELECT SET_CONFIG('app.current_tenant','11',true)"),
            Arguments.of("set_config, schema-qualified", "SELECT pg_catalog.set_config('app.current_tenant','11',true)"),
            Arguments.of("set_config poisoning the pooled session",
                         "SELECT set_config('statement_timeout','1',false)"),
            Arguments.of("query_to_xml (the audit)",
                         "SELECT query_to_xml('select count(*) from connector.connector', false, false, '')::text AS x "
                             + "FROM connector.t_zombie_workorder_p1 w LIMIT 1"),
            Arguments.of("query_to_xml_and_xmlschema", "SELECT query_to_xml_and_xmlschema('select 1', false, false, '')"),
            Arguments.of("query_to_xmlschema", "SELECT query_to_xmlschema('select 1', false, false, '')"),
            Arguments.of("table_to_xml", "SELECT table_to_xml('connector.connector', false, false, '')"),
            Arguments.of("table_to_xmlschema", "SELECT table_to_xmlschema('connector.connector', false, false, '')"),
            Arguments.of("table_to_xml_and_xmlschema", "SELECT table_to_xml_and_xmlschema('connector.connector', false, false, '')"),
            Arguments.of("cursor_to_xml", "SELECT cursor_to_xml('c', 10, false, false, '')"),
            Arguments.of("cursor_to_xmlschema", "SELECT cursor_to_xmlschema('c', false, false, '')"),
            Arguments.of("schema_to_xml", "SELECT schema_to_xml('connector', false, false, '')"),
            Arguments.of("schema_to_xmlschema", "SELECT schema_to_xmlschema('connector', false, false, '')"),
            Arguments.of("schema_to_xml_and_xmlschema", "SELECT schema_to_xml_and_xmlschema('connector', false, false, '')"),
            Arguments.of("database_to_xml", "SELECT database_to_xml(false, false, '')"),
            Arguments.of("database_to_xmlschema", "SELECT database_to_xmlschema(false, false, '')"),
            Arguments.of("database_to_xml_and_xmlschema", "SELECT database_to_xml_and_xmlschema(false, false, '')"),
            Arguments.of("ts_stat, which also runs a query held in a string",
                         "SELECT * FROM ts_stat('select to_tsvector(name) from connector.connector')"),
            Arguments.of("ts_rewrite, whose text argument is a query too",
                         "SELECT ts_rewrite('a'::tsquery, 'SELECT to_tsquery(name), ''a''::tsquery FROM connector.connector_mapping')"),
            Arguments.of("other sessions' SQL, read backend by backend",
                         "SELECT pg_stat_get_backend_activity(id) FROM pg_stat_get_backend_idset() id"),
            Arguments.of("a session advisory lock on a writer's key",
                         "SELECT pg_advisory_lock(hashtextextended('connector.t_x', 0))"),
            Arguments.of("a try advisory lock", "SELECT pg_try_advisory_lock(1)"),
            Arguments.of("pg_sleep_for", "SELECT pg_sleep_for('4 seconds')"),
            Arguments.of("pg_sleep_until", "SELECT pg_sleep_until(now() + interval '4 seconds')"),
            Arguments.of("pg_read_binary_file", "SELECT pg_read_binary_file('/etc/passwd')"),
            Arguments.of("pg_class, unqualified", "SELECT relname FROM pg_class"),
            Arguments.of("pg_attribute, unqualified", "SELECT attname FROM pg_attribute"),
            Arguments.of("pg_policies, unqualified", "SELECT * FROM pg_policies"),
            Arguments.of("pg_roles, unqualified", "SELECT rolname FROM pg_roles"),
            Arguments.of("pg_stat_activity: other tenants' SQL in flight", "SELECT query FROM pg_stat_activity"),
            Arguments.of("pg_cancel_backend", "SELECT pg_cancel_backend(42)"),
            Arguments.of("pg_terminate_backend", "SELECT pg_terminate_backend(42)"));
    }

    @ParameterizedTest(name = "refuses {0}")
    @MethodSource("boundaryFunctions")
    void refusesAFunctionThatMovesTheBoundaryOrRunsSqlFromAString(String rule, String sql) {
        assertThatThrownBy(() -> guard.check(sql))
            .as("must refuse %s", rule)
            .isInstanceOf(SqlGuard.SqlRejectedException.class)
            .hasMessageContaining("banned token in SQL");
    }

    /**
     * The denylist reads NAMES, and a zero-width character makes a different name — for the server
     * too, which is what makes this fail-open safe rather than a bypass. Measured on PostgreSQL 16
     * under the empty {@code search_path}: {@code set_config<U+200B>('app.current_tenant','11',true)}
     * answers {@code function set_config<U+200B>(unknown, unknown, boolean) does not exist}, and
     * {@code query_to_xml<U+200B>(…)} the same.
     *
     * <p>Before the identifier class became the server's, our lexer split such a name and the denylist
     * caught the fragment. The agreement is the stronger property: a name we read as ONE token is a
     * name the server reads as one token, so an entry stepped around here can only name something
     * that does not exist. Pinned so the reasoning is visible if the class ever narrows again.
     */
    @Test
    void aZeroWidthCharacterMakesADifferentName_whichTheServerCannotResolveEither() {
        String evaded = "set_config\u200B";
        String call = evaded + "('app.current_tenant','11',true)";

        assertThat(SqlLexer.tokenize(call).get(0).text())
            .as("one token, and not the banned name — the same reading the server gives it")
            .isEqualTo(evaded);
        assertThat(effectiveText(guard.check("SELECT " + call)))
            .as("so the denylist does not fire, and the server refuses a function that does not exist")
            .contains(evaded);
        assertThatThrownBy(() -> guard.check("SELECT set_config('app.current_tenant','11',true)"))
            .as("the real name is still refused here, before it reaches the server")
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

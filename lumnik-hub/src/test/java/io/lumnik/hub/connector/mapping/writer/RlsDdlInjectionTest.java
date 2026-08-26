package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DDL {@link RlsDdl} emits for auto-created dynamic tables cannot be broken out of by a
 * hostile {@code schema}/{@code name} pair from a connector manifest. The identifier reaches this
 * class UNQUOTED, and {@code polrelid = '...'::regclass} embeds the quoted identifier inside a
 * SINGLE-QUOTED string literal, then the whole statement lives inside a {@code DO $$ ... $$} block —
 * two escaping contexts {@link io.lumnik.hub.sql.SqlIdentifiers#quoteIdent} does not cover (it only
 * doubles {@code "}). Because this DDL runs on the {@code admin} datasource (BYPASSRLS), a successful
 * break-out reads and writes every tenant's data.
 *
 * <p>Assertions are exact-string comparisons against a hand-computed expected DO block (same style as
 * {@link RlsDdlCharacterizationTest}) so a payload that terminates its context early shows up as a
 * mismatch, not merely as a missing substring.
 */
class RlsDdlInjectionTest {

    /** The dollar-quote tag {@code RlsDdl} must use for every {@code DO} block in the class. */
    private static final String TAG = "$lumnik_rls$";

    @Test
    void tenantIsolationPolicy_doubles_a_single_quote_inside_the_regclass_literal() {
        // "o'r", quoted via quoteIdent (which only doubles '"'), becomes the Java string "o'r"
        // wrapped in double quotes: "\"o'r\"" — the embedded ' would close the regclass string
        // literal early on unfixed code, letting whatever text follows run as extra PL/pgSQL.
        String sql = RlsDdl.tenantIsolationPolicy(null, "o'r");

        String expected =
            "DO " + TAG + " BEGIN " +
            "  IF NOT EXISTS (" +
            "    SELECT 1 FROM pg_policy " +
            "    WHERE polname = 'tenant_isolation' AND polrelid = '\"o''r\"'::regclass" +
            "  ) THEN " +
            "    CREATE POLICY tenant_isolation ON \"o'r\"" +
            "      USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint); " +
            "  END IF; " +
            "END " + TAG;

        assertThat(sql).isEqualTo(expected);

        // Round-trip the escaped literal back to prove it represents the ORIGINAL quoted identifier,
        // not a truncated or corrupted one: doubling then un-doubling must be lossless.
        String literal = sql.substring(
            sql.indexOf("polrelid = '") + "polrelid = '".length(),
            sql.indexOf("'::regclass"));
        assertThat(literal.replace("''", "'")).isEqualTo("\"o'r\"");
    }

    @Test
    void tenantIsolationPolicy_dollar_tag_cannot_be_closed_by_the_identifier() {
        // No double quote to double here — the attack is purely against the DO $$ ... $$ boundary:
        // an identifier containing a literal "$$" would close a bare-"$$"-tagged block early.
        String sql = RlsDdl.tenantIsolationPolicy("connector", "orders$$evil");

        String qualified = "\"connector\".\"orders$$evil\"";
        String expected =
            "DO " + TAG + " BEGIN " +
            "  IF NOT EXISTS (" +
            "    SELECT 1 FROM pg_policy " +
            "    WHERE polname = 'tenant_isolation' AND polrelid = '" + qualified + "'::regclass" +
            "  ) THEN " +
            "    CREATE POLICY tenant_isolation ON " + qualified +
            "      USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint); " +
            "  END IF; " +
            "END " + TAG;

        assertThat(sql).isEqualTo(expected);
        // The identifier's own "$$" must NOT create a second matching pair of the CHOSEN tag: the
        // tag appears exactly twice (open + close), regardless of what the identifier injects.
        assertThat(countOccurrences(sql, TAG)).isEqualTo(2);
    }

    @Test
    void guarded_dollar_tag_cannot_be_closed_by_an_identifier_embedded_in_a_wrapped_statement() {
        // guarded() has no string-literal context (its statements are plain GRANT DDL), so the only
        // exposure is the DO $$ ... $$ boundary itself — same "$$"-in-identifier attack as above,
        // reached through a grant statement built from a hostile table name.
        String grant = RlsDdl.grantAppDml("connector", "orders$$evil");
        String sql = RlsDdl.guarded(grant);

        String expected = "DO " + TAG + " BEGIN   " + grant + "; EXCEPTION WHEN OTHERS THEN NULL; END " + TAG;

        assertThat(sql).isEqualTo(expected);
        assertThat(countOccurrences(sql, TAG)).isEqualTo(2);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}

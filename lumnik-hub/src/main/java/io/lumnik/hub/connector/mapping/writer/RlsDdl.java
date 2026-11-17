package io.lumnik.hub.connector.mapping.writer;

import java.util.List;
import java.util.regex.Pattern;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The tenant-isolation DDL applied to every auto-created dynamic table, in ONE place.
 *
 * <p>Both dynamic writers create tables outside the migration path — {@link DynamicTableWriter} for
 * {@code connector.t_*}, {@link HybridJsonbWriter} for the hybrid {@code ext.*} tables — and both
 * must stamp the same {@code tenant_isolation} policy on what they create. They used to carry their
 * own copy of that DDL, so the V21/V31 policy-shape fix had to be applied by hand to both. This
 * class is the single source: a fix lands once, on every dynamic table.
 *
 * <p>Callers pass UNQUOTED identifiers; quoting happens here via
 * {@link io.lumnik.hub.sql.SqlIdentifiers}. A {@code null} schema means an unqualified table.
 *
 * <p>Both writers grant the same set (app DML, readonly select, sequence usage) and both issue
 * <b>one guarded block per grant</b>. That is a rule, not a style: a guarded block's handler
 * aborts the whole block, so grants sharing one block are
 * cancelled by the first that fails — and the javadoc below says plainly that the target roles
 * may not exist. Grouping once put the readonly SELECT second behind the app's DML.
 * {@link #guarded} takes a single statement so that arrangement cannot be written again;
 * {@code RlsDdlCharacterizationTest} asserts the resulting one-grant-per-block property over the
 * writers' output, so a grant added later inherits it.
 *
 * <p>No default privilege covers the readonly grants since V93 (#2008), so a failed one must not pass in silence:
 * {@link #readonlySelectGrant} comes out ALREADY guarded against a missing role only. The hybrid writer guards every
 * grant that way (#2010): its DDL is one transaction under an exclusive lock, and no default privilege covers
 * {@code ext} at all. DynamicTableWriter keeps {@link #guarded} for its app grants.
 */
final class RlsDdl {

    /** The RLS-enforced application role every grant here targets. */
    private static final String TO_APP_ROLE = " TO lumnik_app";

    /**
     * Dollar-quote tag for every {@code DO} block this class emits, instead of the bare {@code $$}.
     * An identifier reaching here is only ever double-quoted (see {@link #qualify}), never validated
     * as a plain identifier by this class itself — a double-quoted Postgres identifier can legally
     * contain a literal {@code $} (or even {@code $$}), which would close a bare-{@code $$}-tagged
     * block early. This tag adds the {@code lumnik_rls} word around the dollars, so closing it
     * requires the identifier to contain this exact literal substring, not just any two dollars.
     */
    private static final String TAG = "$lumnik_rls$";

    /** {@code DO} as the first word, whatever follows it: a space, a newline, a dollar tag. */
    private static final Pattern NESTED_BLOCK = Pattern.compile("\\s*DO(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);

    private RlsDdl() {
    }

    static String enableRowLevelSecurity(String schema, String table) {
        return "ALTER TABLE " + qualify(schema, table) + " ENABLE ROW LEVEL SECURITY";
    }

    /**
     * The {@code tenant_isolation} policy, wrapped in an existence check so re-creating an already
     * policed table is a no-op ({@code CREATE POLICY} has no {@code IF NOT EXISTS}).
     *
     * <p>V21 guard: an empty GUC degrades to zero rows, never 22P02 (V31 healed older tables).
     * {@code current_setting(…, true)} returns the empty string rather than raising when
     * {@code app.current_tenant} was never set, and an unguarded {@code ''::bigint} would then fail
     * the whole query with an invalid-text-representation error instead of simply hiding the rows.
     * {@code NULLIF} turns that empty string into NULL, and {@code tenant_id = NULL} is never true —
     * so a request without a tenant sees nothing. Fail closed, never fail loud.
     *
     * <p>{@code polrelid = '...'::regclass} embeds the quoted identifier inside a SINGLE-QUOTED
     * string literal — a second escaping context {@code quoteIdent} does not cover (it only doubles
     * {@code "}). A {@code '} in the identifier is doubled ({@code replace("'", "''")}) for THAT
     * literal only; the {@code CREATE POLICY ... ON} clause uses the identifier unescaped, as SQL
     * identifier syntax, which is where it belongs.
     */
    static String tenantIsolationPolicy(String schema, String table) {
        String t = qualify(schema, table);
        return "DO " + TAG + " BEGIN " +
               "  IF NOT EXISTS (" +
               "    SELECT 1 FROM pg_policy " +
               "    WHERE polname = 'tenant_isolation' AND polrelid = " + literal(t) + "::regclass" +
               "  ) THEN " +
               "    CREATE POLICY tenant_isolation ON " + t +
               "      USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint); " +
               "  END IF; " +
               "END " + TAG;
    }

    /**
     * Btree index leading with {@code tenant_id}. {@link #tenantIsolationPolicy} puts an implicit
     * {@code tenant_id = ?} predicate on every RLS-enforced read against this table — view renders,
     * dormancy sweeps, ask-to-SQL — so without this index each of those sequentially scans it.
     *
     * <p>Only {@link DynamicTableWriter} needs this: {@link HybridJsonbWriter}'s {@code ext.*} tables
     * already get a tenant_id-leading index for free from their
     * {@code UNIQUE (tenant_id, external_id)} constraint.
     */
    static String tenantIdIndex(String schema, String table) {
        String t = qualify(schema, table);
        String idxName = table + "_idx_tenant_id";
        return "CREATE INDEX IF NOT EXISTS " + quoteIdent(idxName) + " ON " + t + " (tenant_id)";
    }

    /** The app role's row access — the datasource the request path reads and writes through. */
    static String grantAppDml(String schema, String table) {
        return "GRANT INSERT, SELECT, UPDATE, DELETE ON " + qualify(schema, table) + TO_APP_ROLE;
    }

    /**
     * The read-only role backs the ask-to-SQL path ({@code SqlGuard.runReadOnly}); without this
     * grant freshly-ingested tables are unreadable there ("permission denied") even though they
     * ingested fine.
     */
    static String grantReadonlySelect(String schema, String table) {
        return "GRANT SELECT ON " + qualify(schema, table) + " TO lumnik_readonly";
    }

    /** {@link #grantReadonlySelect}, guarded against a missing role only: for DDL that runs as one transaction. */
    static String readonlySelectGrant(String schema, String table) {
        return guardedAgainstMissingRole(grantReadonlySelect(schema, table));
    }

    static String grantSequenceUsage(String schema, String sequence) {
        return "GRANT USAGE, SELECT ON SEQUENCE " + qualify(schema, sequence) + TO_APP_ROLE;
    }

    /**
     * The hybrid writer's grants (#2010): the app's DML and its sequence, the readonly SELECT, both roles' USAGE on the
     * schema. One block each, guarded against a missing role only (its DDL is one transaction under an exclusive lock),
     * and each made only where it is missing: a grant a relation already has still rewrites its catalog row, which drops
     * every session's cached plans on it. So a lost grant comes back, and a healthy table is left as it is.
     */
    static List<String> hybridGrants(String schema, String table) {
        String rel = literal(qualify(schema, table));
        // A long table's sequence is not <table>_id_seq: Postgres shortens the table part to keep 63 characters.
        String sequence = "pg_get_serial_sequence(" + rel + ", 'id')";
        return List.of(
            ifMissing(privileges("has_table_privilege('lumnik_app', " + rel, "INSERT", "SELECT", "UPDATE", "DELETE"),
                grantAppDml(schema, table)),
            ifMissing(privileges("has_table_privilege('lumnik_readonly', " + rel, "SELECT"),
                grantReadonlySelect(schema, table)),
            ifMissing(privileges("has_sequence_privilege('lumnik_app', " + sequence, "USAGE", "SELECT"),
                "EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s" + TO_APP_ROLE + "', " + sequence + ")"),
            ifMissing(privileges("has_schema_privilege('lumnik_app', " + literal(schema), "USAGE"),
                "GRANT USAGE ON SCHEMA " + quoteIdent(schema) + TO_APP_ROLE),
            ifMissing(privileges("has_schema_privilege('lumnik_readonly', " + literal(schema), "USAGE"),
                "GRANT USAGE ON SCHEMA " + quoteIdent(schema) + " TO lumnik_readonly"));
    }

    /** {@code call(..., 'P')} for each privilege, AND-ed: one call naming several answers whether ANY is held. */
    private static String privileges(String callHead, String... privileges) {
        StringBuilder all = new StringBuilder();
        for (String privilege : privileges) {
            if (!all.isEmpty()) all.append(" AND ");
            all.append(callHead).append(", '").append(privilege).append("')");
        }
        return all.toString();
    }

    private static String ifMissing(String held, String grant) {
        return guardedAgainstMissingRole("IF NOT (" + held + ") THEN " + grant + "; END IF");
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * Wraps ONE grant in a {@code DO} block that swallows failures — the parameter is a single
     * statement on purpose, not for want of varargs. The handler aborts its block, so statements
     * sharing one are cancelled by the first that fails, silently; and failing is ordinary, per
     * the next sentence. Grouping is therefore not a caller's choice to make, and this signature
     * is what says so — a javadoc saying it was tried, and a sibling test rotted unnoticed within
     * the same change. {@code RlsDdlGuardedSemanticsIT} pins the abort behaviour against a real
     * Postgres. Grants are no-ops when
     * the executing user already owns the table (test mode) and the target roles may not exist at
     * all there — neither case must break table creation. In production {@code lumnik_admin} owns
     * the table and the grants are the real thing.
     */
    static String guarded(String statement) {
        return block(statement, "OTHERS");
    }

    private static String block(String statement, String condition) {
        // a DO block inside another, both tagged TAG, would end the outer one early: every table creation would fail
        if (NESTED_BLOCK.matcher(statement).lookingAt()) {
            throw new IllegalArgumentException("already a guarded block: " + statement);
        }
        return "DO " + TAG + " BEGIN   " + statement + "; EXCEPTION WHEN " + condition + " THEN NULL; END " + TAG;
    }

    /**
     * {@link #guarded} for a grant no default privilege covers, in DDL that runs as one transaction (#2008): failed, it
     * would leave a relation its role cannot reach, and nothing would say why. Only the expected failure is swallowed —
     * the role is absent ({@code undefined_object}, a plain {@code %test} database); any other one stops the DDL that
     * asked for it, and rolls it back.
     */
    static String guardedAgainstMissingRole(String statement) {
        return block(statement, "undefined_object");
    }

    private static String qualify(String schema, String table) {
        return schema == null ? quoteIdent(table) : quoteIdent(schema) + '.' + quoteIdent(table);
    }
}

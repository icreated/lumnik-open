package io.lumnik.hub.connector.mapping.writer;

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
 * <p>Grant composition deliberately stays at each call site — {@link DynamicTableWriter} issues one
 * guarded block per grant, {@link HybridJsonbWriter} groups all of its into one — but both writers
 * now grant the same set (app DML, readonly select, sequence usage), and the statements themselves
 * come from here.
 */
final class RlsDdl {

    /**
     * Dollar-quote tag for every {@code DO} block this class emits, instead of the bare {@code $$}.
     * An identifier reaching here is only ever double-quoted (see {@link #qualify}), never validated
     * as a plain identifier by this class itself — a double-quoted Postgres identifier can legally
     * contain a literal {@code $} (or even {@code $$}), which would close a bare-{@code $$}-tagged
     * block early. This tag adds the {@code lumnik_rls} word around the dollars, so closing it
     * requires the identifier to contain this exact literal substring, not just any two dollars.
     */
    private static final String TAG = "$lumnik_rls$";

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
        String literal = t.replace("'", "''");
        return "DO " + TAG + " BEGIN " +
               "  IF NOT EXISTS (" +
               "    SELECT 1 FROM pg_policy " +
               "    WHERE polname = 'tenant_isolation' AND polrelid = '" + literal + "'::regclass" +
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
        return "GRANT INSERT, SELECT, UPDATE, DELETE ON " + qualify(schema, table) + " TO lumnik_app";
    }

    /**
     * The read-only role backs the ask-to-SQL path ({@code SqlGuard.runReadOnly}); without this
     * grant freshly-ingested tables are unreadable there ("permission denied") even though they
     * ingested fine.
     */
    static String grantReadonlySelect(String schema, String table) {
        return "GRANT SELECT ON " + qualify(schema, table) + " TO lumnik_readonly";
    }

    static String grantSequenceUsage(String schema, String sequence) {
        return "GRANT USAGE, SELECT ON SEQUENCE " + qualify(schema, sequence) + " TO lumnik_app";
    }

    /**
     * Schema-level USAGE the app role needs to reach anything inside a schema
     * {@code HybridJsonbWriter} auto-creates for its {@code ext.*} tables — that schema is not
     * covered by any migration's grant (schema-usage-grant-gap). Lives here, not hand-rolled at
     * the call site, so it shares this class's single {@link #TAG} instead of duplicating it.
     */
    static String grantSchemaUsageApp(String schema) {
        return "GRANT USAGE ON SCHEMA " + quoteIdent(schema) + " TO lumnik_app";
    }

    /** Same USAGE grant for the read-only role (ask-to-SQL). */
    static String grantSchemaUsageReadonly(String schema) {
        return "GRANT USAGE ON SCHEMA " + quoteIdent(schema) + " TO lumnik_readonly";
    }

    /**
     * Wraps grant statements in a {@code DO} block that swallows failures. Grants are no-ops when
     * the executing user already owns the table (test mode) and the target roles may not exist at
     * all there — neither case must break table creation. In production {@code lumnik_admin} owns
     * the table and the grants are the real thing.
     */
    static String guarded(String... statements) {
        StringBuilder sb = new StringBuilder("DO ").append(TAG).append(" BEGIN ");
        for (String statement : statements) {
            sb.append("  ").append(statement).append("; ");
        }
        return sb.append("EXCEPTION WHEN OTHERS THEN NULL; END ").append(TAG).toString();
    }

    private static String qualify(String schema, String table) {
        return schema == null ? quoteIdent(table) : quoteIdent(schema) + '.' + quoteIdent(table);
    }
}

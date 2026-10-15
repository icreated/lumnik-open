package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;

import java.sql.Connection;
import java.sql.Statement;

/**
 * The physical source table a {@code kind: Entity} fixture manifest needs before
 * {@code /api/entities/apply} can compile its view — {@code EntityApplyResource} /
 * {@code EntityViewCompiler} refuses with {@code 409} otherwise (a deliberate, honest refusal,
 * not a 500). Shared by {@code ExportApplyResourceIT}, {@code ExportRegistryResourceIT} and
 * {@code ExportManifestTenantIsolationIT} — same six-statement recipe (drop, create, RLS, policy,
 * grant), differing only in the table/view name and the source columns.
 *
 * <p>The recipe runs in one transaction under a short {@code SET LOCAL lock_timeout}: the DDL needs
 * exclusive locks, and a session that still holds the table must fail the setup with SQLSTATE
 * {@code 55P03} in seconds, not stall the suite: a DDL setup once waited for the full 1800 s
 * snapshot timeout (#755); a leaked snapshot-read transaction is the leading hypothesis, not an
 * established cause. {@code SET LOCAL} ends with the transaction, so nothing leaks into the pooled admin
 * connection. Harness containment only — it bounds what a stall costs, not why it happens (#761).
 */
public final class EntitySourceTableFixture {

    private static final String LOCK_TIMEOUT = "10s";

    private EntitySourceTableFixture() {}

    /** {@code viewName} is the compiled view's own name ({@code "v_" + entity name, lowercased}),
     *  dropped first so a re-run doesn't collide with a stale compile. */
    public static void create(AgroalDataSource adminDs, String tableName, String viewName,
                               String... textColumns) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
            st.execute("DROP VIEW IF EXISTS connector.\"" + viewName + "\"");
            st.execute("DROP TABLE IF EXISTS connector." + tableName);
            StringBuilder columns = new StringBuilder("tenant_id bigint NOT NULL");
            for (String col : textColumns) columns.append(", \"").append(col).append("\" text");
            st.execute("CREATE TABLE connector." + tableName + " (" + columns + ")");
            st.execute("ALTER TABLE connector." + tableName + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + tableName + "_iso ON connector." + tableName
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + tableName + " TO lumnik_app");
            c.commit();
        }
    }
}

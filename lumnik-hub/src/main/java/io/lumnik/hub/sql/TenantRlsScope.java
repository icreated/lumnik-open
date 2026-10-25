package io.lumnik.hub.sql;

import io.quarkus.narayana.jta.QuarkusTransaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Scopes a read-only JDBC connection to one tenant before a raw query runs against
 * the generic hub schema (fused views, connector tables). One source of truth for the
 * RLS boundary the raw-JDBC readers share — a divergence here would be a cross-tenant
 * leak, so it lives in exactly one place.
 *
 * <p><b>Precondition — call only from within an active transaction.</b> {@code SET LOCAL}
 * is transaction-scoped: on an autocommit connection each statement commits into its own
 * empty transaction and is discarded before the query, silently leaving RLS unenforced.
 *
 * <p>That precondition is now <b>enforced</b>, not merely documented. The guard this class
 * previously ruled out tested {@code getAutoCommit()}, which is unusable here because the
 * {@code %test} profile pins {@code readonly.jdbc.transactions=DISABLED} and so makes every
 * test connection autoCommit — including correct {@code @Transactional} paths.
 * {@link QuarkusTransaction#isActive()} asks the transaction manager instead of the
 * connection, so it is independent of datasource enlistment and works under {@code %test}.
 *
 * <p>Why it earns its keep: the precondition was broken twice on 2026-08-11, in ways no test
 * could see. Removing {@code @Transactional} from the RAG chat door left the ask path without
 * an ambient transaction, and promoting {@code readonly.jdbc.transactions=disabled} to all
 * profiles made every readonly connection autoCommit even under a {@code @Transactional}
 * caller. Both silently unbound the tenant. A silent zero-rows is a bad failure mode for
 * something carrying tenant isolation; failing loudly is the point.
 */
public final class TenantRlsScope {

    private TenantRlsScope() {
    }

    /**
     * Pin the connection to {@code tenantId} for the ambient (request) transaction.
     * Read-only-ness is not this method's job: it is carried by the {@code lumnik_readonly}
     * role the datasource connects as (default_transaction_read_only=on, SELECT-only grants).
     *
     * <p>{@code SET LOCAL app.current_tenant} scopes RLS on hub tables to this tenant's
     * rows. {@code SET LOCAL} rides the ambient transaction — bound to it, no leak onto
     * the pooled connection, and no manual tx control (the connection may be JTA-enlisted).
     *
     * <p>{@code SET LOCAL search_path = ''} forces fully-qualified table refs: a BARE
     * table name does NOT resolve to a connector table, so an unqualified name cannot
     * bypass LeakGuard (which inspects schema-qualified refs) to read another scope's
     * table via the search path. {@code pg_catalog} functions still resolve (implicitly
     * searched), and qualified {@code connector.x} refs work.
     *
     * <p>{@code SET LOCAL TimeZone = 'UTC'} ({@link TemporalText#utcSession}): every reader that
     * scopes a connection here shows or compares dates, and must do so whatever zone the hub runs in
     * (#1304, #1325).
     */
    public static void applyTo(Connection c, long tenantId) throws SQLException {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException(
                "TenantRlsScope.applyTo requires an active transaction: SET LOCAL is "
                + "transaction-scoped, so without one the tenant binding and the empty "
                + "search_path are discarded before the query and RLS goes unenforced. "
                + "Wrap the read in QuarkusTransaction.requiringNew() or make the caller "
                + "@Transactional.");
        }
        // No JDBC setReadOnly here, deliberately: Agroal (since Quarkus 3.33) refuses to
        // modify read-only state on a JTA-enlisted connection — every production caller is
        // enlisted, so the call 500'd all four readers (caught by the OTK publish proof,
        // never by %test, whose readonly datasource does not enlist). Read-only-ness is not
        // lost: this datasource runs as lumnik_readonly, whose ROLE carries
        // default_transaction_read_only=on and SELECT-only grants — the physical firewall
        // is the role, not a driver flag. TenantRlsScopeEnlistedIT pins the enlisted posture.
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
            st.execute("SET LOCAL search_path = ''");
        }
        TemporalText.utcSession(c);
    }
}

package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import jakarta.transaction.UserTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;

/** Shared fixtures for event/webhook/kafka ITs: tx-with-rollback-on-throw, admin-DS SQL, webhook seeding. */
public final class EventItSupport {
    private EventItSupport() {}

    /** Run body in a UserTransaction as system; ALWAYS rolls back on throw (no ARJUNA016051 fork-poison). */
    public static <T> T runInTxAsSystem(UserTransaction utx, Callable<T> body) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                T r = body.call();
                utx.commit();
                return r;
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* already inactive */ }
                throw new RuntimeException(e);
            }
        });
    }

    /** Run a void body in a system tx (rollback-on-throw). */
    public static void runInTxAsSystem(UserTransaction utx, RunnableEx body) {
        runInTxAsSystem(utx, () -> { body.run(); return null; });
    }

    @FunctionalInterface public interface RunnableEx { void run() throws Exception; }

    /** Insert an enabled webhook_endpoint (tenant 1) subscribed to the given event types. */
    public static void seedWebhookEndpoint(AgroalDataSource adminDs, UserTransaction utx,
                                           String name, String... eventTypes) {
        String arr = "{" + String.join(",", eventTypes) + "}";
        runInTxAsSystem(utx, () -> {
            try (Connection c = adminDs.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, "
                     + "auth_mode, event_types, enabled) VALUES (1, ?, 'http://it.test/x', 'generic', "
                     + "'NONE', CAST(? AS TEXT[]), TRUE)")) {
                ps.setString(1, name);
                ps.setString(2, arr);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Deletes every {@code webhook_endpoint} whose name matches {@code namePattern} (SQL LIKE),
     * together with its deliveries — the one safe way to do it while {@code WebhookConsumer} is
     * ticking every 5 s.
     *
     * <p>Deleting the deliveries and then the endpoint as two autocommit statements leaves a
     * window; a tick landing inside it fails the parent delete on
     * {@code webhook_delivery_endpoint_id_fkey}, and because a teardown that throws leaves an
     * <b>enabled</b> endpoint behind in a global table, the row-event gate stays ON for whichever
     * IT runs next. That is the 2026-07-24 nightly, one race and seven failures.
     *
     * <p>Locking the endpoints {@code FOR UPDATE} first is what closes it, not luck or ordering: an
     * insert into {@code webhook_delivery} must take {@code FOR KEY SHARE} on the row it references,
     * which conflicts with {@code FOR UPDATE}. A tick already in flight is waited for, a tick
     * arriving later is held off until the commit — and by then its parent is gone, so it fails on
     * its own side instead of corrupting the teardown. Both deletes ride the same transaction.
     */
    public static void purgeWebhookEndpoints(AgroalDataSource adminDs, String namePattern) {
        String match = "SELECT id FROM core.webhook_endpoint WHERE name LIKE ?";
        try (Connection c = adminDs.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement lock = c.prepareStatement(match + " FOR UPDATE");
                 PreparedStatement deliveries = c.prepareStatement(
                     "DELETE FROM core.webhook_delivery WHERE endpoint_id IN (" + match + ")");
                 PreparedStatement endpoints = c.prepareStatement(
                     "DELETE FROM core.webhook_endpoint WHERE name LIKE ?")) {
                lock.setString(1, namePattern);
                // Drain the cursor: a row is only locked once fetched. With the driver's default
                // fetchSize (0) the whole set comes back in one round trip and the point is moot —
                // but the guarantee this method sells must not rest on an unstated default, since a
                // pattern matching several endpoints would then lock only the first page.
                try (ResultSet locked = lock.executeQuery()) {
                    while (locked.next()) { /* fetching IS the locking */ }
                }
                deliveries.setString(1, namePattern);
                deliveries.executeUpdate();
                endpoints.setString(1, namePattern);
                endpoints.executeUpdate();
                c.commit();
                c.setAutoCommit(autoCommit);
            } catch (Exception e) {
                // Unwinding a teardown: whatever went wrong here IS the diagnosis, so neither
                // cleanup step may replace it. The 07-24 nightly was only readable because its FK
                // violation reached the log intact; a rollback failing on a dead connection would
                // have buried it. They ride along as suppressed instead.
                suppressing(e, c::rollback);
                suppressing(e, () -> c.setAutoCommit(autoCommit));
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("purgeWebhookEndpoints failed for '" + namePattern + "'", e);
        }
    }

    /** Runs a cleanup step that must never replace the failure it is cleaning up after. */
    private static void suppressing(Exception primary, RunnableEx step) {
        try {
            step.run();
        } catch (Exception cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    public static void execSql(AgroalDataSource adminDs, String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) { st.execute(sql); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static long scalar(AgroalDataSource adminDs, String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : -1L; }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}

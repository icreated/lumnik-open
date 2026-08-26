package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.lumnik.hub.testsupport.EventItSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the 2026-07-24 nightly failure deterministically, and pins the property that closes
 * it.
 *
 * <p>{@code WebhookConsumer} runs {@code @Scheduled(every = "5s")} for the whole IT campaign,
 * inserting {@code webhook_delivery} rows for every enabled endpoint. Teardowns that delete their
 * deliveries and then their endpoint in two separate autocommit statements leave a window: one
 * tick landing inside it makes the parent delete fail on
 * {@code webhook_delivery_endpoint_id_fkey}. That is what happened — and because the teardown
 * threw, an enabled endpoint subscribed to {@code data.row.changed} survived in a <b>global</b>
 * table, flipping the row-event gate ON for the sibling ITs that ran next. One race, seven
 * failures.
 *
 * <p>The interleaving is forced here rather than raced for: a tick that has <b>inserted but not
 * yet committed</b> is invisible to the purge's delivery delete, while its foreign-key
 * {@code FOR KEY SHARE} lock on the endpoint row is exactly what the parent delete collides with.
 * Locking the endpoints {@code FOR UPDATE} first inverts the collision — the purge now waits for
 * the tick instead of racing it, then deletes both ends in one transaction.
 */
@QuarkusTest
class WebhookEndpointPurgeIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject UserTransaction utx;

    @Test
    void purge_survives_a_delivery_inserted_while_it_runs() throws Exception {
        String name = "purge-it-" + System.nanoTime();
        seedWebhookEndpoint(adminDs, utx, name, "data.row.changed");
        long id = scalar(adminDs, "SELECT id FROM core.webhook_endpoint WHERE name = '" + name + "'");
        assertTrue(id > 0, "the endpoint must be seeded");

        Thread tick = startInFlightTick(id);

        // The teardown gesture every event IT performs. It must not care that a tick is in flight.
        assertDoesNotThrow(() -> purgeWebhookEndpoints(adminDs, name),
            "a delivery landing mid-teardown must not break the purge — this is the 07-24 failure");

        joinOrFail(tick);
        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM core.webhook_endpoint WHERE name = '" + name + "'"),
            "the endpoint must be gone, or it flips the global gate ON for sibling ITs");
        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM core.webhook_delivery WHERE endpoint_id = " + id),
            "its deliveries must go with it");
    }

    /**
     * Every caller passes a LIKE pattern matching several endpoints, so the lock has to cover all of
     * them — not just whichever row came back first. The tick targets the LAST match.
     */
    @Test
    void purge_locks_every_matched_endpoint_not_only_the_first() throws Exception {
        String prefix = "purge-many-" + System.nanoTime() + "-";
        for (int i = 0; i < 3; i++) {
            seedWebhookEndpoint(adminDs, utx, prefix + i, "data.row.changed");
        }
        long lastId = scalar(adminDs,
            "SELECT max(id) FROM core.webhook_endpoint WHERE name LIKE '" + prefix + "%'");

        Thread tick = startInFlightTick(lastId);

        assertDoesNotThrow(() -> purgeWebhookEndpoints(adminDs, prefix + "%"),
            "the lock must cover every matched endpoint, not the first page of them");

        joinOrFail(tick);
        assertEquals(0, scalar(adminDs,
            "SELECT count(*) FROM core.webhook_endpoint WHERE name LIKE '" + prefix + "%'"),
            "all three endpoints must be gone");
    }

    /**
     * A consumer tick caught mid-flight: the delivery is inserted and deliberately NOT committed, so
     * it stays invisible to the purge's delete while its FK lock is already held on the endpoint.
     * Daemon, so a wedged tick can never hold the JVM — and therefore the build — alive.
     */
    private Thread startInFlightTick(long endpointId) throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread tick = new Thread(() -> {
            try (Connection c = adminDs.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO core.webhook_delivery (tenant_id, endpoint_id, event_type, payload) "
                        + "VALUES (1, ?, 'data.row.changed', '{}'::jsonb)")) {
                    ps.setLong(1, endpointId);
                    ps.executeUpdate();
                }
                inserted.countDown();
                // Hold the FK lock long enough that the purge cannot simply win by being faster.
                Thread.sleep(500);
                c.commit();
            } catch (Exception e) {
                failure.set(e);
            } finally {
                inserted.countDown();
            }
        }, "webhook-consumer-tick");
        tick.setDaemon(true);
        tick.start();
        assertTrue(inserted.await(10, TimeUnit.SECONDS), "the tick must reach its uncommitted insert");
        assertNull(failure.get(), () -> "the tick itself must not fail: " + failure.get());
        return tick;
    }

    /** A tick still alive after the join means it wedged — say so instead of leaving it running. */
    private void joinOrFail(Thread tick) throws Exception {
        tick.join(TimeUnit.SECONDS.toMillis(10));
        if (tick.isAlive()) {
            tick.interrupt();
            fail("the tick thread wedged — it should have committed and exited");
        }
    }

    @Test
    void purge_removes_endpoint_and_deliveries_without_contention() throws Exception {
        String name = "purge-it-calm-" + System.nanoTime();
        seedWebhookEndpoint(adminDs, utx, name, "data.row.changed");
        long id = scalar(adminDs, "SELECT id FROM core.webhook_endpoint WHERE name = '" + name + "'");
        execSql(adminDs, "INSERT INTO core.webhook_delivery (tenant_id, endpoint_id, event_type, payload) "
            + "VALUES (1, " + id + ", 'data.row.changed', '{}'::jsonb)");

        purgeWebhookEndpoints(adminDs, name);

        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM core.webhook_endpoint WHERE name = '" + name + "'"));
        assertEquals(0, scalar(adminDs, "SELECT count(*) FROM core.webhook_delivery WHERE endpoint_id = " + id));
    }
}

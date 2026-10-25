package io.lumnik.hub.event.consumer;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared sweep mechanics for outbox cursor consumers: RLS sentinel, per-tenant
 * switch, pessimistic lock on the cursor row (serializes concurrent sweeps of
 * the same consumer), unconditional cursor advance.
 *
 * Must run in system mode (wrap in {@link io.lumnik.common.tenant.TenantContext#runAsSystem}):
 * sweep reads across all tenants via the '0' sentinel and fails closed otherwise.
 *
 * Processors MUST NOT rely on throwing for retry: a processor exception is
 * logged and the cursor still advances (no head-of-line blocking). Consumers
 * that need redelivery own their retry state (e.g. webhook_delivery).
 *
 * A consumer tick {@link #drain drains} the log: batch after batch, each in its own
 * transaction, while they come back full and the tick's budget lasts.
 *
 * Each event is processed in a transaction of its own, inside the batch's: the batch
 * transaction holds only the cursor, so one event's failed database statement costs that
 * event and never the batch (#943).
 *
 * A processor that calls the outside world (an HTTP POST) must not do it inside the batch
 * transaction, which holds the cursor's pessimistic lock and a pooled connection: it is a
 * {@link BatchProcessor}, recording the work in {@code accept} and doing it in {@code afterCommit}.
 */
@ApplicationScoped
public class OutboxSweeper {

    private static final Logger LOG = Logger.getLogger(OutboxSweeper.class);

    /**
     * Wall-clock budget of one consumer tick: no new batch starts once it is spent (the batch in
     * flight always finishes). Shorter than the consumers' 5 s schedule, so a tick normally ends
     * before the next one is due; one that overruns makes the scheduler skip the next tick —
     * every consumer tick is {@code concurrentExecution = SKIP} — never run it alongside.
     */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(4);

    @Inject EntityManager em;

    /**
     * One consumer tick. {@code perBatch} supplies a fresh processor for every batch, so a
     * consumer can keep what it reads per event (its tenants' subscriptions) for exactly one batch.
     */
    public void drain(String consumerName, int batchSize, Supplier<? extends BatchProcessor> perBatch) {
        drain(consumerName, batchSize, TICK_BUDGET, perBatch);
    }

    void drain(String consumerName, int batchSize, Duration budget, Supplier<? extends BatchProcessor> perBatch) {
        long deadline = System.nanoTime() + budget.toNanos();
        int swept;
        do {
            // A NEW transaction per batch, never one around the loop: neither the transaction
            // timeout nor a rollback ever spans more than one batch. Explicit, rather than relying
            // on sweep's @Transactional being intercepted on a self-call. A batch that throws ends
            // the tick — it is not retried within it.
            BatchProcessor processor = perBatch.get();
            swept = QuarkusTransaction.requiringNew().call(() -> sweep(consumerName, batchSize, processor));
            // Only a batch that committed gets here: what it recorded (and its cursor) is durable.
            processor.afterCommit();
        } while (swept == batchSize && System.nanoTime() - deadline < 0);
    }

    /**
     * One batch, in one transaction. Returns how many events it read: a full batch means more may
     * wait. A consumer tick calls {@link #drain}, never this alone (one batch per tick was #900's
     * ceiling); tests call it to sweep exactly one batch.
     */
    @Transactional
    public int sweep(String consumerName, int batchSize, Consumer<EventOutboxRow> processor) {
        // Fail closed: sweep sets the cross-tenant sentinel '0' directly, bypassing the
        // interceptor's spoofing guard. It must only ever run from genuine system mode, so a
        // tenant-request caller (REST/CDI) that forgot runAsSystem can't escalate to all tenants.
        if (!TenantContext.isSystemMode()) {
            throw new IllegalStateException(
                "OutboxSweeper.sweep must run in system mode — wrap the call in TenantContext.runAsSystem");
        }
        // Sentinel '0' = system mode for the cross-tenant pick (event_outbox policy, V4/V21).
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();

        EventConsumerPosition pos =
            em.find(EventConsumerPosition.class, consumerName, LockModeType.PESSIMISTIC_WRITE);
        if (pos == null) {
            // Race-safe first creation: two concurrent first-sweeps must not collide on the PK.
            em.createNativeQuery(
                "INSERT INTO core.event_consumer (consumer_name) VALUES (:name) ON CONFLICT (consumer_name) DO NOTHING")
              .setParameter("name", consumerName)
              .executeUpdate();
            pos = em.find(EventConsumerPosition.class, consumerName, LockModeType.PESSIMISTIC_WRITE);
        }

        // Only what is decided, in (xact_id, id) order (#1570): an id cursor alone moved past a lower id
        // a long transaction committed later, and the event was never read — no webhook, no email, no
        // decision. Below the horizon no row can still appear, so nothing behind the cursor is lost.
        // The price: a row waits while an older transaction is open (EventHorizonWatch says so).
        @SuppressWarnings("unchecked")
        List<EventOutboxRow> batch = em.createNativeQuery(
                "SELECT * FROM core.event_outbox WHERE " + EventOutboxRow.DECIDED
                + " AND (xact_id, id) > (:xact, :id) ORDER BY xact_id, id", EventOutboxRow.class)
            .setParameter("xact", pos.getLastXactId())
            .setParameter("id", pos.getLastEventId())
            .setMaxResults(batchSize)
            .getResultList();

        for (EventOutboxRow row : batch) {
            try {
                // The event's database work runs in a transaction of its own (#943): a failed
                // statement aborts a PostgreSQL transaction, and on the batch's own one that would
                // roll the cursor back with it and replay the batch every tick, forever.
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("SET LOCAL app.current_tenant = '" + row.getTenantId() + "'")
                      .executeUpdate();
                    CurrentTenantHolder.set(row.getTenantId());
                    processor.accept(row);
                });
            } catch (RuntimeException e) {
                LOG.errorf(e, "Consumer %s failed on event %d (%s) — advancing past it",
                           consumerName, row.getId(), row.getEventType());
            } finally {
                CurrentTenantHolder.clear();
            }
            pos.advanceTo(row.getXactId(), row.getId());
        }
        return batch.size();
    }
}

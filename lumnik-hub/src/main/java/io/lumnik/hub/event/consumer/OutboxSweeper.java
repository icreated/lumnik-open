package io.lumnik.hub.event.consumer;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Consumer;

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
 * Known trade-off (deliberate, revisit in É2.b): the whole batch — including any
 * HTTP delivery a processor performs — runs inside one transaction holding the
 * cursor's pessimistic lock. Single-instance @Scheduled(SKIP) ticks make this
 * tolerable; a hung endpoint stalls the cursor for the batch. If throughput ever
 * matters, deliver outside the cursor transaction and advance in a short tx.
 */
@ApplicationScoped
public class OutboxSweeper {

    private static final Logger LOG = Logger.getLogger(OutboxSweeper.class);

    @Inject EntityManager em;

    @Transactional
    public void sweep(String consumerName, int batchSize, Consumer<EventOutboxRow> processor) {
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

        List<EventOutboxRow> batch = em.createQuery(
                "FROM EventOutboxRow WHERE id > :after ORDER BY id", EventOutboxRow.class)
            .setParameter("after", pos.getLastEventId())
            .setMaxResults(batchSize)
            .getResultList();

        for (EventOutboxRow row : batch) {
            try {
                em.createNativeQuery("SET LOCAL app.current_tenant = '" + row.getTenantId() + "'")
                  .executeUpdate();
                CurrentTenantHolder.set(row.getTenantId());
                processor.accept(row);
            } catch (RuntimeException e) {
                LOG.errorf(e, "Consumer %s failed on event %d (%s) — advancing past it",
                           consumerName, row.getId(), row.getEventType());
            } finally {
                CurrentTenantHolder.clear();
                em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            }
            pos.advanceTo(row.getId());
        }
    }
}

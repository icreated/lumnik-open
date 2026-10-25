package io.lumnik.hub.event;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Says when events wait (#1570).
 *
 * <p>Every outbox reader — the webhook, email and process consumers and the live tail — reads a row
 * only once its transaction is decided ({@link EventOutboxRow#DECIDED}): older than the oldest
 * transaction still open. That is what keeps a late commit from being skipped. Its price is a wait:
 * while an older transaction stays open, newer events are held — every tenant's, since the horizon
 * is the database server's, not a tenant's: transaction ids are cluster-wide, so a write left open
 * in another database on the same PostgreSQL server holds them too. A session left idle in
 * transaction would hold them without a word; this check writes the word.
 */
@ApplicationScoped
public class EventHorizonWatch {

    private static final Logger LOG = Logger.getLogger(EventHorizonWatch.class);

    /** How long an event may wait before the hub says so. A normal open transaction lasts seconds. */
    static final Duration PATIENCE = Duration.ofMinutes(2);

    @Inject EntityManager em;

    @Scheduled(every = "60s", delayed = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        TenantContext.runAsSystem(this::check);
    }

    /** How many committed events have waited longer than {@link #PATIENCE}; a warning when any has. */
    @Transactional
    public long check() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        long waiting = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM core.event_outbox WHERE NOT (" + EventOutboxRow.DECIDED + ")"
                + " AND created_at < now() - make_interval(secs => :secs)")
            .setParameter("secs", PATIENCE.toSeconds())
            .getSingleResult()).longValue();
        if (waiting > 0) {
            LOG.warnf("%d event(s) have waited more than %d minutes for an older database transaction to end. "
                      + "Webhooks, emails, processes and the live event tail deliver them when it ends — "
                      + "look for a database session left idle in transaction.",
                      waiting, PATIENCE.toMinutes());
        }
        return waiting;
    }
}

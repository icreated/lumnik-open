package io.lumnik.hub.event;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Purges events every consumer has passed AND older than the retention window.
 * Never purges under a lagging consumer; no registered consumer = no purge.
 */
@ApplicationScoped
public class EventRetentionPurger {

    private static final Logger LOG = Logger.getLogger(EventRetentionPurger.class);
    private static final long STALE_CURSOR_LAG_WARN = 500_000;

    @Inject EntityManager em;

    @ConfigProperty(name = "lumnik.events.retention-days", defaultValue = "30")
    int retentionDays;

    @Scheduled(cron = "0 30 4 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        TenantContext.runAsSystem(this::purge);
    }

    @Transactional
    public void purge() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        Number floor = (Number) em.createNativeQuery(
            "SELECT MIN(last_event_id) FROM core.event_consumer").getSingleResult();
        // floor == null: no consumer registered. floor == 0 is valid: a consumer exists but hasn't consumed yet (id <= 0 matches nothing).
        if (floor == null) return;

        Number maxId = (Number) em.createNativeQuery(
            "SELECT MAX(id) FROM core.event_outbox").getSingleResult();
        if (maxId != null && maxId.longValue() - floor.longValue() > STALE_CURSOR_LAG_WARN) {
            LOG.warnf("EventRetentionPurger: floor %d lags max event id %d by more than %d — "
                      + "retention is pinned to the slowest consumer cursor. Only if that consumer "
                      + "has been decommissioned (not merely lagging — e.g. a node that is down), "
                      + "drop its abandoned cursor to let retention advance: "
                      + "DELETE FROM core.event_consumer WHERE consumer_name = '<decommissioned>'. "
                      + "Deleting a live consumer's cursor makes it permanently miss every event "
                      + "purged below the new floor.",
                      floor.longValue(), maxId.longValue(), STALE_CURSOR_LAG_WARN);
        }

        int purged = em.createNativeQuery("""
            DELETE FROM core.event_outbox
            WHERE id <= :floor AND created_at < NOW() - make_interval(days => :days)
            """)
            .setParameter("floor", floor.longValue())
            .setParameter("days", retentionDays)
            .executeUpdate();
        if (purged > 0) LOG.infof("EventRetentionPurger: purged %d event(s) (floor=%d, retention=%dd)",
                                  purged, floor.longValue(), retentionDays);
    }
}

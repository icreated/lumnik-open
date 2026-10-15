package io.lumnik.hub.webhook;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Bounds the delivery journal (#940): {@code core.webhook_delivery} gets a row per event × endpoint
 * plus one per replay, each carrying the POSTed payload and the receiver's response body, and was kept
 * forever. One daily purge (05:00, after the event purge at 04:30 and the audit purge at 04:45), admin
 * datasource — time-based and tenant-blind by design, like both.
 *
 * <p>{@code lumnik.webhooks.delivery-retention-days} (env {@code LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS})
 * defaults to <b>90</b>: long enough to troubleshoot an integration and replay a delivery, short enough to
 * bound the payloads kept. A value {@code <= 0} disables the purge: keep-forever is an explicit operator
 * choice, logged once a day, never a silent accident. It is deliberately not the events window — the
 * outbox serves consumers, this journal serves whoever is debugging an integration.
 *
 * <p>Only finished deliveries go: succeeded, or failed with every attempt spent. A row the retry sweep
 * still owns ({@code succeeded = FALSE AND attempts < 3}, which includes a first delivery not yet
 * POSTed) or holds a lease on is never deleted — the predicate says so rather than relying on retries
 * ending within a minute — and {@code FOR UPDATE SKIP LOCKED} passes over a row being claimed right now.
 * Replay re-sends a delivery's own payload, so a purged delivery is the first thing that can no longer be
 * replayed.
 *
 * <p>Deleted in bounded batches, each its own transaction (the admin datasource does not enlist in JTA),
 * so the first run over a large history is not one huge transaction.
 */
@ApplicationScoped
public class WebhookDeliveryRetentionPurger {

    private static final Logger LOG = Logger.getLogger(WebhookDeliveryRetentionPurger.class);
    private static final int BATCH = 5_000;

    /** Parameters: the window in days, then the batch size. Oldest first, through idx_webhook_delivery_attempted_at. */
    static final String PURGE_BATCH_SQL = """
        DELETE FROM core.webhook_delivery
         WHERE id IN (
             SELECT id FROM core.webhook_delivery
              WHERE attempted_at < NOW() - make_interval(days => ?)
                AND (succeeded OR attempts >= 3)
                AND (claimed_until IS NULL OR claimed_until < NOW())
              ORDER BY attempted_at
              LIMIT ?
              FOR UPDATE SKIP LOCKED)
        """;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @ConfigProperty(name = "lumnik.webhooks.delivery-retention-days", defaultValue = "90")
    int retentionDays;

    @Scheduled(cron = "0 0 5 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        purge();
    }

    public void purge() {
        purgeWithRetention(retentionDays);
    }

    void purgeWithRetention(int days) {
        if (days <= 0) {
            LOG.info("WebhookDeliveryRetentionPurger: retention disabled (<= 0) — deliveries are kept forever by operator choice");
            return;
        }
        try {
            int purged = purgeWithRetention(days, BATCH);
            if (purged > 0) LOG.infof("WebhookDeliveryRetentionPurger: purged %d delivery row(s) older than %dd", purged, days);
        } catch (Exception e) {
            LOG.error("WebhookDeliveryRetentionPurger: purge failed — retrying tomorrow", e);
        }
    }

    /** Purges batch by batch until one comes back short; returns the rows deleted. */
    int purgeWithRetention(int days, int batch) {
        int total = 0;
        int purged;
        do {
            purged = Jdbc.update(adminDs, "WebhookDeliveryRetentionPurger.purge", PURGE_BATCH_SQL, ps -> {
                ps.setInt(1, days);
                ps.setInt(2, batch);
            });
            total += purged;
        } while (purged == batch);
        return total;
    }
}

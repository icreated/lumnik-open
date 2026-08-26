package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Bounds the audit log (P9): {@code core.audit_event} snapshots logins/emails and was kept
 * forever — the GDPR storage-limitation tension the security page names. One bounded DELETE
 * per day (04:45, after the event purge at 04:30), admin datasource — retention is
 * time-based and tenant-blind by design, exactly like the event purge.
 *
 * <p>{@code lumnik.audit.retention-days} (env {@code LUMNIK_AUDIT_RETENTION_DAYS}) defaults
 * to <b>365</b> — audit posture wants a year, storage limitation wants a bound. A value
 * {@code <= 0} disables the purge entirely: keep-forever is an explicit operator choice,
 * logged at startup-visible INFO once a day, never a silent accident.
 *
 * <p>The table is declared PARTITION BY RANGE but only the DEFAULT partition exists today —
 * so retention is a DELETE, not a partition drop; V40 gives {@code occurred_at} the index
 * the daily cutoff scan needs. Real partition management can replace this when volume asks.
 */
@ApplicationScoped
public class AuditRetentionPurger {

    private static final Logger LOG = Logger.getLogger(AuditRetentionPurger.class);

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @ConfigProperty(name = "lumnik.audit.retention-days", defaultValue = "365")
    int retentionDays;

    @Scheduled(cron = "0 45 4 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        purge();
    }

    public void purge() {
        purgeWithRetention(retentionDays);
    }

    void purgeWithRetention(int days) {
        if (days <= 0) {
            LOG.info("AuditRetentionPurger: retention disabled (<= 0) — audit rows are kept forever by operator choice");
            return;
        }
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM core.audit_event WHERE occurred_at < NOW() - make_interval(days => ?)")) {
            ps.setInt(1, days);
            int purged = ps.executeUpdate();
            if (purged > 0) LOG.infof("AuditRetentionPurger: purged %d audit row(s) older than %dd", purged, days);
        } catch (Exception e) {
            LOG.error("AuditRetentionPurger: purge failed — retrying tomorrow", e);
        }
    }
}

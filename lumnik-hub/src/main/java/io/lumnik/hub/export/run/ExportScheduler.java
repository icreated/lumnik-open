package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Cross-tenant export scheduler: every minute, for each export with a {@code spec.schedule}, asks
 * {@link DueRule} for the latest owed slot and, when no run of that occurrence exists yet in any
 * status, starts it through the same {@link ExportRunner#start} a manual run uses.
 *
 * <p>Discovery and the existence check run on the admin datasource (BYPASSRLS) — they learn which
 * contracts are due, never a single exported row. The run itself carries the stored row's tenant;
 * no request identity exists here and none is inferred. "Any status" is what keeps a failed
 * occurrence from being retried every minute; {@code uq_export_run_occurrence_attempt} is what
 * lets exactly one replica in when two tick together.
 */
@Startup
@ApplicationScoped
public class ExportScheduler {

    private static final Logger LOG = Logger.getLogger(ExportScheduler.class);

    private static final String SELECT_SCHEDULED =
        "SELECT tenant_id, name, schedule, created_at FROM connector.export_manifest " +
        "WHERE schedule IS NOT NULL";

    private static final String OCCURRENCE_EXISTS =
        "SELECT 1 FROM connector.export_run " +
        "WHERE tenant_id = ? AND export_name = ? AND occurrence = ? LIMIT 1";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ExportRunner runner;
    @Inject ExportRunReaper reaper;

    /**
     * #897: how many SCHEDULED export runs may be in flight at once ON THIS HUB INSTANCE. Each holds
     * one pooled {@code admin} connection (its RunLock) and one {@code readonly} connection for its
     * whole snapshot; a nightly {@code 0 2 * * *} shared by many exports used to start them all in
     * one tick. The default 2 keeps them to 2 of the default 20 {@code admin} slots, beside scheduled
     * ingestion's bound in {@code ConnectorEngine}. A due slot that finds no free permit is not
     * started and writes nothing, so the next tick finds it still due; one that waits past the
     * export's next slot is replaced by it (no back-fill).
     *
     * <p>With the run queue off the manual and retry doors ({@link ExportRunner#start}) take no permit:
     * they answer with a claimed {@code Running} row, which is never committed without its lock held, so
     * they cannot wait without holding a slot (#952). With {@code lumnik.run-queue.enabled} they queue a
     * row instead and the {@link ExportRunDispatcher} draws on THIS SAME budget (D11). Per instance, like {@code max-concurrent-rebuilds}: N
     * replicas allow N &times; this value, each against its own pool.
     */
    @ConfigProperty(name = "lumnik.export.max-concurrent-scheduled-runs", defaultValue = "2")
    int maxConcurrentScheduledRuns;

    private Semaphore runPermits;

    /** Run at boot ({@code @Startup}), not on the first tick: a bad value stops the hub instead. */
    @PostConstruct
    void init() {
        if (maxConcurrentScheduledRuns < 1) {
            // Zero permits would leave every scheduled export due for ever, without a word.
            throw new IllegalStateException("lumnik.export.max-concurrent-scheduled-runs must be at least 1, was "
                + maxConcurrentScheduledRuns);
        }
        runPermits = new Semaphore(maxConcurrentScheduledRuns);
    }

    /** Package-private: {@code ExportRunSchedulingIT} holds the permits to see a due slot wait. */
    Semaphore runPermits() {
        return runPermits;
    }

    record ScheduledExport(long tenantId, String name, String schedule, Instant createdAt) {}

    private record DueSlot(ScheduledExport export, Instant occurrence) {}

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void scheduledTick() {
        // Reap first: a crashed run holds uq_export_run_in_flight and would refuse this tick's start.
        reaper.reapOnTick();
        try {
            int n = tick(Instant.now());
            if (n > 0) LOG.infof("ExportScheduler: started %d export runs", n);
        } catch (Exception e) {
            LOG.error("ExportScheduler.tick() failed; will retry in 1m", e);
        }
    }

    /**
     * One tick at {@code now}; the number of runs it started. Exposed for tests. The oldest owed slot
     * goes first (#897): with few run permits, an export due every few minutes must not take every
     * freed permit ahead of a nightly one until the nightly slot is replaced by the next.
     */
    int tick(Instant now) {
        List<DueSlot> due = new ArrayList<>();
        for (ScheduledExport e : scheduled()) {
            try {
                DueRule.occurrence(e.schedule(), e.createdAt(), now).ifPresent(o -> due.add(new DueSlot(e, o)));
            } catch (Exception ex) {
                // One bad export (an unparseable cron) never stops the tick.
                LOG.warnf(ex, "ExportScheduler: export %s (tenant %d) not started", e.name(), e.tenantId());
            }
        }
        due.sort(Comparator.comparing(DueSlot::occurrence));
        int started = 0;
        for (DueSlot d : due) {
            try {
                if (startIfDue(d.export(), d.occurrence())) started++;
            } catch (Exception ex) {
                // Nor does one that cannot start (a vanished row).
                LOG.warnf(ex, "ExportScheduler: export %s (tenant %d) not started", d.export().name(), d.export().tenantId());
            }
        }
        return started;
    }

    private boolean startIfDue(ScheduledExport e, Instant occurrence) {
        if (occurrenceExists(e, occurrence)) return false;
        if (!runPermits.tryAcquire()) {
            LOG.debugf("ExportScheduler: export %s occurrence %s due, but no run slot is free — the next tick retries",
                e.name(), occurrence);
            return false;
        }
        ExportRunner.Launch launch;
        try {
            launch = runner.launch(new ExportRunRequest(e.tenantId(), e.name(),
                ExportTrigger.SCHEDULE, null, occurrence, occurrence, 1, null));
        } catch (RuntimeException ex) {
            runPermits.release();
            throw ex;
        }
        // Back once the run has released its lock; at once when nothing started.
        launch.worker().whenComplete((ignored, failure) -> runPermits.release());
        StartResult result = launch.result();
        if (result instanceof StartResult.Started s) {
            LOG.debugf("ExportScheduler: export %s occurrence %s started as run %s", e.name(), occurrence, s.runId());
            return true;
        }
        // AlreadyRunning / OccurrenceTaken: another replica or a manual run got there first.
        LOG.debugf("ExportScheduler: export %s occurrence %s not started: %s", e.name(), occurrence, result);
        return false;
    }

    /** Package-private, like {@link #occurrenceExists}: {@code ExportSchedulerOldestSlotFirstTest} stands in for both. */
    List<ScheduledExport> scheduled() {
        return Jdbc.query(adminDs, "ExportScheduler scheduled exports", SELECT_SCHEDULED, ps -> {},
            rs -> new ScheduledExport(rs.getLong(1), rs.getString(2), rs.getString(3),
                Jdbc.instant(rs, 4)));
    }

    boolean occurrenceExists(ScheduledExport e, Instant occurrence) {
        return Jdbc.exists(adminDs, "ExportScheduler occurrence claimed", OCCURRENCE_EXISTS, ps -> {
            ps.setLong(1, e.tenantId());
            ps.setString(2, e.name());
            ps.setObject(3, occurrence.atOffset(ZoneOffset.UTC));
        });
    }
}

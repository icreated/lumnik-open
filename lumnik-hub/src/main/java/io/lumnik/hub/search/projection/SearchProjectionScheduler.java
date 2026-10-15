package io.lumnik.hub.search.projection;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Cross-tenant tick (design doc §5a): reap first, then purge-drain (independent of any tenant's
 * currently-valid indices, D-purge-drain), then discovery's rebuild/catch-up dispatch. Catch-and-log
 * at the tick level — a second catch per (tenant, index) inside {@link SearchProjectionWorker} means
 * one bad pair never aborts the rest of the same tick — while this outer layer keeps one failing
 * step (reap, purge-drain, discovery) from silencing the others' own logging.
 */
@ApplicationScoped
public class SearchProjectionScheduler {

    private static final Logger LOG = Logger.getLogger(SearchProjectionScheduler.class);

    @Inject SearchProjectionRunReaper reaper;
    @Inject SearchProjectionWorker worker;

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void scheduledTick() {
        runStep("reap", reaper::reap);
        runStep("purgeDrain", worker::purgeDrain);
        runStep("discoveryTick", worker::discoveryTick);
    }

    /** One tick step, isolated: a failure here is logged and retried next tick, never stopping the
     *  OTHER steps in this same tick from running. */
    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            LOG.errorf(e, "SearchProjectionScheduler: %s failed; will retry in 1m", name);
        }
    }
}

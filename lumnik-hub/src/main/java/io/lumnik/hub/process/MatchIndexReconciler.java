package io.lumnik.hub.process;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Keeps the indexes of every watched Entity built (#1838). The wanted set is derived from what is stored — the
 * enabled processes on an Entity view, and the stored Entity — so a restart, a re-applied Entity (its key
 * expression may have changed with no new Process), a node that missed a change, and an apply whose own background
 * build was interrupted are all caught by the next pass, without a table of work.
 *
 * <p>Each pass reads the Entity as stored, not this node's registry copy (#1836): what is to be indexed is what the
 * view is built from.
 */
@ApplicationScoped
public class MatchIndexReconciler {

    private static final Logger LOG = Logger.getLogger(MatchIndexReconciler.class);

    @Inject ProcessManifestStore store;
    @Inject MatchIndexes matchIndexes;

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void scheduledTick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.error("MatchIndexReconciler.sweep() failed; will retry in 1m", e);
        }
    }

    /** Exposed for tests: one pass over every tenant's enabled process on an Entity. */
    public void sweep() {
        for (String view : store.allEnabledEntityViews()) {
            matchIndexes.storedEntity(view).ifPresent(matchIndexes::ensureAll);
        }
    }
}

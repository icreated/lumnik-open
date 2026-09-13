package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observes the very in-process event ask's {@code SchemaCardRefresher} observes, so a test can ask
 * whether a run asked for its schema cards to be refreshed.
 *
 * <p>Read through METHODS, never the field. This is {@code @ApplicationScoped}, so what a test
 * injects is a client proxy: a method call delegates to the instance, and a FIELD access reads the
 * proxy's own — a different, forever-empty list. The first version of this spy was read as
 * {@code spy.seen} and timed out for BOTH endings, which looked exactly like the defect it was
 * written to catch. What settled it was asking whether the ALREADY WORKING path (Completed) reached
 * the spy: it did not, so the spy was the broken thing.
 */
@ApplicationScoped
public class SchemaRefreshSpy {

    private final List<UUID> seen = new CopyOnWriteArrayList<>();

    void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
        seen.add(ev.runId());
    }

    /** Whether a refresh was asked for this run. A method, so the proxy delegates. */
    public boolean saw(UUID runId) {
        return seen.contains(runId);
    }

    public void forget() {
        seen.clear();
    }
}

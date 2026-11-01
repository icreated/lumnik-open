package io.lumnik.hub.search.projection;

import io.quarkus.arc.Arc;
import io.quarkus.scheduler.Scheduler;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The background projection tick, paused around each test (#1672). {@code SearchProjectionScheduler} runs every minute
 * behind every {@code @QuarkusTest}, and under {@code -Psearch} a provider is present, so it really projects: when it
 * lands between a test's {@code sweep()} and its own {@code catchUp}, it drains the marks into the real provider and
 * the test's {@code RecordingSink} receives nothing. Calling a worker by hand is unaffected, only the trigger is paused.
 */
public class ProjectionTickPaused implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        scheduler().pause(SearchProjectionScheduler.TICK_IDENTITY);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        scheduler().resume(SearchProjectionScheduler.TICK_IDENTITY);
    }

    private static Scheduler scheduler() {
        return Arc.container().instance(Scheduler.class).get();
    }
}

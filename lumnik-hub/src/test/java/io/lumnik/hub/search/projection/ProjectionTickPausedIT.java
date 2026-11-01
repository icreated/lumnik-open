package io.lumnik.hub.search.projection;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/** The pause {@link ProjectionTickPaused} promises the projection ITs (#1672): the tick has a name, and it is paused. */
@QuarkusTest
@ExtendWith(ProjectionTickPaused.class)
class ProjectionTickPausedIT {

    @Inject Scheduler scheduler;

    @Test
    void the_projection_tick_is_paused_while_a_test_runs() {
        assertThat(scheduler.getScheduledJob(SearchProjectionScheduler.TICK_IDENTITY)).as("the tick is scheduled by name").isNotNull();
        assertThat(scheduler.isPaused(SearchProjectionScheduler.TICK_IDENTITY)).isTrue();
    }
}

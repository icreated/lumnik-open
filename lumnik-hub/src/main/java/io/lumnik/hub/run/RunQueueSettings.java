package io.lumnik.hub.run;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * The run queue's two knobs (#952), shared by both dispatchers. The poll cadence is not here: it is
 * the {@code every} of each dispatcher's {@code @Scheduled} (default {@code 5s},
 * {@code lumnik.run-queue.poll-interval}).
 */
@ApplicationScoped
public class RunQueueSettings {

    /**
     * Whether the doors queue at all: the flag gates EMISSION only. The dispatchers always start or expire what is
     * queued, so switching it off drains the leftovers instead of stranding them (spec D6). Global to the deployment: turn it on only once no replica older than the release that can read
     * {@code Queued} is left, or that replica throws on the first row it meets.
     */
    @ConfigProperty(name = "lumnik.run-queue.enabled", defaultValue = "false")
    boolean enabled;

    /** How long a run may wait for a start before it is ended: a policy for work nobody could start. */
    @ConfigProperty(name = "lumnik.run-queue.max-age", defaultValue = "1h")
    Duration maxAge;

    public boolean enabled() {
        return enabled;
    }

    public Duration maxAge() {
        return maxAge;
    }

    void onStart(@Observes StartupEvent ev) {
        validate();
    }

    void validate() {
        if (maxAge.isZero() || maxAge.isNegative()) {
            // Zero would expire every row the moment it is queued, without a word.
            throw new IllegalStateException("lumnik.run-queue.max-age must be positive, was " + maxAge);
        }
    }
}

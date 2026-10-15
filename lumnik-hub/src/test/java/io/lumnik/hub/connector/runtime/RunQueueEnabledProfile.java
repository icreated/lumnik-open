package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.event.consumer.SchedulerPausedProfile;

import java.util.HashMap;
import java.util.Map;

/** The scheduler off (the ITs drive the dispatcher by hand) and the run queue on. */
public class RunQueueEnabledProfile extends SchedulerPausedProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("lumnik.run-queue.enabled", "true");
        return overrides;
    }
}

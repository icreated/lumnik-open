package io.lumnik.hub.event.consumer;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Disables the Quarkus scheduler so ITs drive consumers manually, deterministically. */
public class SchedulerPausedProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.scheduler.enabled", "false");
    }
}

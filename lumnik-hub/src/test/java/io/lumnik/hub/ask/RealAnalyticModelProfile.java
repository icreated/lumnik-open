package io.lumnik.hub.ask;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns off the global {@code @Analytic} test mock ({@code MockAnalyticChatPort}, always
 * {@code SELECT 1}) so the real model answers. Every real-model IT uses this one profile.
 */
public class RealAnalyticModelProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("lumnik.judgeeval.mock-analytic", "false");
    }
}

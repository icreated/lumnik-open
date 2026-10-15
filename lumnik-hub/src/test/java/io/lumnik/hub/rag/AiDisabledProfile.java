package io.lumnik.hub.rag;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** A hub booted with the AI family switched off (the operator's {@code LUMNIK_AI_ENABLED=false}). */
public class AiDisabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("lumnik.ai.enabled", "false");
    }
}

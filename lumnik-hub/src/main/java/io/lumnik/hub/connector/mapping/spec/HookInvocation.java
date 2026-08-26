package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;

public record HookInvocation(
    String name,     // e.g. "clean", "parse-date", "aggregator"
    JsonNode params  // raw params (each hook parses its own schema)
) {}

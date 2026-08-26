package io.lumnik.hub.connector.rest.auth;

import java.util.List;
import java.util.Map;

public record AuthSpec(String kind, Map<String, String> config, List<String> healthWarnings) {}

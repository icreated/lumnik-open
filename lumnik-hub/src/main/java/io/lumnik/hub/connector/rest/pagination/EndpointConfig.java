package io.lumnik.hub.connector.rest.pagination;

import java.util.Map;

public record EndpointConfig(String baseUrl, String method, String path, Map<String, Object> raw) {}

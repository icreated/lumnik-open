package io.lumnik.hub.webhook;

import java.util.List;

public record IntegrationCreate(
    String name,
    String targetUrl,
    String platform,
    AuthMode authMode,
    String secret,
    List<String> eventTypes,
    Boolean enabled
) {}

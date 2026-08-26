package io.lumnik.hub.webhook;

import java.util.List;

public record IntegrationUpdate(
    String name,
    String targetUrl,
    String platform,
    AuthMode authMode,
    String secret,           // null = keep existing; non-null = rotate
    List<String> eventTypes,
    Boolean enabled
) {}

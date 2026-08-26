package io.lumnik.hub.webhook;

import java.util.List;

public record IntegrationDto(
    Long id,
    String name,
    String targetUrl,
    String platform,
    AuthMode authMode,
    List<String> eventTypes,
    boolean enabled,
    boolean hasSecret
) {
    public static IntegrationDto from(WebhookEndpoint e) {
        return new IntegrationDto(
            e.getId(),
            e.getName(),
            e.getTargetUrl(),
            e.getPlatform(),
            e.getAuthMode(),
            e.getEventTypes(),
            e.isEnabled(),
            e.getSecretEncrypted() != null
        );
    }
}

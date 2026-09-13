package io.lumnik.hub.api.connector;

import java.util.Map;

/**
 * The flat fields the TUI's {@code connector apply} posts — the shape {@link ConnectorAdminDto}
 * writes. {@code window} is read here for the same reason {@code schedule} is: this door is a full
 * replace, and a field the read door emits but the write door ignores is a silent loss.
 */
public record ConnectorCreateRequest(
    Long tenantId,
    String name,
    String typeId,
    String schedule,
    String window,
    boolean enabled,
    Map<String, Object> config
) {}

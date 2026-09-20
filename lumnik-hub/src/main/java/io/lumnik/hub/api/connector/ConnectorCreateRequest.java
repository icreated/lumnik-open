package io.lumnik.hub.api.connector;

import java.util.List;
import java.util.Map;

/**
 * The flat fields the TUI's {@code connector apply} posts — the shape {@link ConnectorAdminDto}
 * writes. {@code window} and {@code tags} are read here for the same reason {@code schedule} is:
 * this door is a full replace, and a field the read door emits but the write door ignores is a
 * silent loss.
 *
 * <p>{@code tags} is the one exception to "full replace" on {@code update} — see
 * {@code ConnectorAdminResource.update}'s handling of a {@code null} value there. It doubles as
 * the {@code scope:<tag>} read boundary, so treating an omitted field as "clear it" would silently
 * broaden a scoped connector to the whole tenant.
 */
public record ConnectorCreateRequest(
    Long tenantId,
    String name,
    String typeId,
    String schedule,
    String window,
    boolean enabled,
    Map<String, Object> config,
    List<String> tags
) {}

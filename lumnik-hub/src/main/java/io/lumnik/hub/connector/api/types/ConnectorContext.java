package io.lumnik.hub.connector.api.types;

import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.helpers.MappingResolver;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public record ConnectorContext(
    UUID runId,
    UUID connectorId,
    UUID endpointId,
    Long tenantId,
    String connectorName,
    String typeId,
    Map<String, Object> config,
    SyncMode syncMode,
    Logger logger,
    CredentialResolver credentials,
    MappingResolver mappings,
    Clock clock
) {
  @SuppressWarnings("unchecked")
  public <T> T configValue(String key, Class<T> type) {
    Object v = config.get(key);
    if (v == null) throw new IllegalArgumentException("Missing config key: " + key);
    if (!type.isInstance(v) && Number.class.isAssignableFrom(type) && v instanceof Number n) {
      if (type == Integer.class) return (T) Integer.valueOf(n.intValue());
      if (type == Long.class)    return (T) Long.valueOf(n.longValue());
      if (type == Double.class)  return (T) Double.valueOf(n.doubleValue());
    }
    return type.cast(v);
  }

  public <T> T configValue(String key, Class<T> type, T defaultValue) {
    if (!config.containsKey(key)) return defaultValue;
    return configValue(key, type);
  }
}

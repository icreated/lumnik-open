package io.lumnik.hub.connector.registry;

import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.exceptions.UnknownConnectorTypeException;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ConnectorTypeRegistry {

  @Inject Instance<ConnectorType> types;
  private final Map<String, ConnectorType> byId = new HashMap<>();

  void onStartup(@Observes StartupEvent ev) {
    for (var t : types) {
      String id = t.id();
      if (byId.containsKey(id))
        throw new IllegalStateException("Duplicate connector type id: " + id +
            " (" + byId.get(id).getClass().getName() + " vs " + t.getClass().getName() + ")");
      byId.put(id, t);
    }
  }

  public ConnectorType find(String id) {
    var t = byId.get(id);
    if (t == null) throw new UnknownConnectorTypeException(id);
    return t;
  }

  public Set<String> all() { return byId.keySet(); }
}

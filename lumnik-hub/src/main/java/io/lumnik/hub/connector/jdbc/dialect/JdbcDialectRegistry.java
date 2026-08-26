package io.lumnik.hub.connector.jdbc.dialect;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class JdbcDialectRegistry {

  @Inject Instance<JdbcDialect> dialectBeans;

  private final Map<String, JdbcDialect> byId = new HashMap<>();

  @PostConstruct
  void init() {
    for (JdbcDialect d : dialectBeans) byId.put(d.id(), d);
  }

  public JdbcDialect resolve(String id) {
    JdbcDialect d = byId.get(id);
    if (d == null)
      throw new IllegalArgumentException(
          "Unsupported dialect '" + id + "' — available: " + byId.keySet());
    return d;
  }
}

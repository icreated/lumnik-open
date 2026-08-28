package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EntityRegistry {

  @Inject
  @DataSource("admin")
  Instance<AgroalDataSource> adminDsInstance;

  @Inject
  Instance<EntityManifestParser> parserInstance;

  private final Map<String, EntityManifest> byName = new ConcurrentHashMap<>();

  @PostConstruct
  void load() {
    if (adminDsInstance.isUnsatisfied()) return;
    loadFromDb();
  }

  /** Reloads all manifests from the DB into a temp map, then swaps atomically. Package-private for testing. */
  void loadFromDb() {
    Map<String, EntityManifest> temp = new LinkedHashMap<>();
    try (Connection c = adminDsInstance.get().getConnection();
         var st = c.createStatement();
         var rs = st.executeQuery("SELECT name, yaml_text FROM connector.entity_manifest")) {
      EntityManifestParser parser = parserInstance.get();
      while (rs.next()) {
        String dbName = rs.getString("name");
        EntityManifest m = parser.parse(rs.getString("yaml_text"));
        if (!m.name().toLowerCase(Locale.ROOT).equals(dbName))
          throw new IllegalStateException("entity_manifest row '" + dbName
              + "' has yaml name '" + m.name() + "' — names diverged, manual intervention required");
        temp.put(dbName, m);
      }
    } catch (Exception e) {
      throw new RuntimeException("EntityRegistry.loadFromDb: " + e, e);
    }
    byName.clear();
    byName.putAll(temp);
  }

  public void put(EntityManifest m) {
    byName.put(m.name().toLowerCase(Locale.ROOT), m);
  }

  public EntityManifest find(String name) {
    return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
  }

  /** Every registered manifest, sorted by name — deterministic iteration for the aggregate reads. */
  public List<EntityManifest> all() {
    return byName.values().stream()
        .sorted(Comparator.comparing(EntityManifest::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}

package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches a Hikari pool per (url, user, tenantId). Tenant in the key prevents
 * accidental cross-tenant sharing. Pools have small fixed size; reuse across
 * connectors of the same source/tenant is the win.
 */
@ApplicationScoped
public class HikariPoolRegistry {

  private static final int DEFAULT_POOL_SIZE = 5;
  private static final long IDLE_TIMEOUT_MS = 30_000L;

  record Key(String url, String user, long tenantId) {}

  private final Map<Key, HikariDataSource> pools = new ConcurrentHashMap<>();

  public HikariDataSource acquire(String url, String user, String password, long tenantId) {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(user, "user");
    Key key = new Key(url, user, tenantId);
    return pools.computeIfAbsent(key, k -> buildPool(url, user, password));
  }

  private HikariDataSource buildPool(String url, String user, String password) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url);
    cfg.setUsername(user);
    cfg.setPassword(password);
    cfg.setReadOnly(true);
    cfg.setMaximumPoolSize(DEFAULT_POOL_SIZE);
    cfg.setIdleTimeout(IDLE_TIMEOUT_MS);
    cfg.setConnectionTimeout(5_000L);
    cfg.setPoolName("jdbc-src-" + Integer.toHexString(url.hashCode()) + "-" + user);
    return new HikariDataSource(cfg);
  }

  /** Returns the number of cached pools. Package-visible for testing. */
  int poolCount() { return pools.size(); }

  public void evictAll() {
    pools.values().forEach(HikariDataSource::close);
    pools.clear();
  }

  @PreDestroy
  void shutdown() { evictAll(); }
}

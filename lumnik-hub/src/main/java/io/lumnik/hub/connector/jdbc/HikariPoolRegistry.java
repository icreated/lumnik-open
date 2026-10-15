package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lumnik.jdbc.spi.JdbcDialect;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches a Hikari pool per (url, user, tenantId, driverClass, dialectId). Tenant in the key prevents
 * accidental cross-tenant sharing; the driver is the dialect provider's own and is loaded explicitly,
 * so a provider installed without its driver fails here with a readable message. The dialect id is part of
 * the key because the provider also decides what runs on every new connection ({@code sessionInitSql}):
 * two providers accepting the same URL with the same driver must not share a pool, or the first to ask
 * would silently configure the session of the second. A dialect reached through an alias is the same
 * dialect, so it is one pool. Pools have small fixed size; reuse across
 * connectors of the same source/tenant is the win.
 */
@ApplicationScoped
public class HikariPoolRegistry {

  private static final int DEFAULT_POOL_SIZE = 5;
  private static final long IDLE_TIMEOUT_MS = 30_000L;

  record Key(String url, String user, long tenantId, String driverClass, String dialectId) {}

  private final Map<Key, HikariDataSource> pools = new ConcurrentHashMap<>();

  public HikariDataSource acquire(String url, String user, String password, long tenantId,
                                  JdbcDialect dialect) {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(dialect, "dialect");
    String driverClass = dialect.driverClass();
    requireDriverOnClasspath(driverClass, dialect);
    Key key = new Key(url, user, tenantId, driverClass, dialect.id());
    return pools.computeIfAbsent(key, k -> buildPool(url, user, password, dialect));
  }

  /** Checked before Hikari so the message names the dialect instead of whatever Hikari wraps. */
  private static void requireDriverOnClasspath(String driverClass, JdbcDialect dialect) {
    try {
      Class.forName(driverClass, false, HikariPoolRegistry.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IllegalStateException("JDBC driver '" + driverClass + "' of dialect '" + dialect.id()
          + "' is not on the classpath", e);
    }
  }

  private HikariDataSource buildPool(String url, String user, String password, JdbcDialect dialect) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url);
    cfg.setDriverClassName(dialect.driverClass());
    dialect.sessionInitSql().ifPresent(cfg::setConnectionInitSql);
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

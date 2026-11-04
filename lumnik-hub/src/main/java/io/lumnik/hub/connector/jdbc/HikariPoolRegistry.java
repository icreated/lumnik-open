package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Caches a Hikari pool per (url, user, tenantId, driverClass, dialectId, password). Tenant in the key prevents
 * accidental cross-tenant sharing; the driver is the dialect provider's own and is loaded explicitly,
 * so a provider installed without its driver fails here with a readable message. The dialect id is part of
 * the key because the provider also decides what runs on every new connection ({@code sessionInitSql}):
 * two providers accepting the same URL with the same driver must not share a pool, or the first to ask
 * would silently configure the session of the second. A dialect reached through an alias is the same
 * dialect, so it is one pool. The password is part of the key because the secret is resolved on every ask:
 * a rotated one must open its connections with the new value, not be served by the pool built with the old.
 * Pools have small fixed size; reuse across connectors of the same source/tenant is the win.
 *
 * <p>A pool lives while it is asked for. One nobody asked for in {@link #UNUSED_POOL_TTL_NANOS} — a deleted
 * connector's, the old one of a rotated password or of a changed url — is closed by a sweep, and never while
 * a connection is lent: closing a Hikari pool aborts the connections still borrowed.
 *
 * <p>A pool is built outside the map's lock. Hikari opens a connection while it is constructed, so a slow or
 * unreachable source would otherwise hold every other key of the same bin, and the sweep, for as long as the
 * driver waits. The map holds the entry at once; one asker builds — whichever reaches the build first —
 * and the others of that key wait for it.
 */
@ApplicationScoped
public class HikariPoolRegistry {

  private static final int DEFAULT_POOL_SIZE = 5;
  private static final long IDLE_TIMEOUT_MS = 30_000L;

  /** A pool nobody asked for in this long is let go; the next ask builds it again. */
  static final long UNUSED_POOL_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

  record Key(String url, String user, long tenantId, String driverClass, String dialectId, String password) {
    // Masked like ResolvedCredential's: a key can end up in a log line or an exception.
    @Override public String toString() {
      return "Key[url=" + url + ", user=" + user + ", tenantId=" + tenantId + ", driverClass=" + driverClass
          + ", dialectId=" + dialectId + ", password=***]";
    }
  }

  /** A pool, built by one of its askers, and the instant it was last asked for. */
  private static final class Held {
    private final FutureTask<HikariDataSource> build;
    // Read and written inside the map's compute calls only: the lock on the key is its synchronisation.
    long askedAtNanos;

    Held(Callable<HikariDataSource> build) { this.build = new FutureTask<>(build); }

    /** Builds the pool if nobody has started to, else waits for the one who did. Rethrows a failed build. */
    HikariDataSource pool() {
      build.run();   // a no-op once started
      try {
        return build.get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException r) throw r;
        if (e.getCause() instanceof Error err) throw err;
        throw new IllegalStateException(e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while the pool was being built", e);
      }
    }

    /** A pool still being built, or whose build failed, has nothing to close. */
    boolean built() { return build.state() == FutureTask.State.SUCCESS; }

    /** The build itself failed — not a caller who stopped waiting for it. */
    boolean failed() { return build.state() == FutureTask.State.FAILED; }

    /** Not asked for since the limit and lending nothing: closing it cuts no query. */
    boolean unusedAt(long nowNanos) {
      return built() && nowNanos - askedAtNanos >= UNUSED_POOL_TTL_NANOS
          && build.resultNow().getHikariPoolMXBean().getActiveConnections() == 0;
    }

    void closeIfBuilt() { if (built()) build.resultNow().close(); }
  }

  private final Map<Key, Held> pools = new ConcurrentHashMap<>();

  public HikariDataSource acquire(String url, String user, String password, long tenantId,
                                  JdbcDialect dialect) {
    return acquire(url, user, password, tenantId, dialect, System.nanoTime());
  }

  /** {@link #acquire} at an instant the caller names, so a test can put the asks and the sweep on one timeline. */
  HikariDataSource acquire(String url, String user, String password, long tenantId,
                           JdbcDialect dialect, long nowNanos) {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(dialect, "dialect");
    String driverClass = dialect.driverClass();
    requireDriverOnClasspath(driverClass, dialect);
    Key key = new Key(url, user, tenantId, driverClass, dialect.id(), password);
    // compute, not computeIfAbsent: stamping the ask and the sweep's decision to close both run under the
    // map's lock on this key, so a sweep cannot close the pool an ask has just been handed. Only the entry is
    // made under the lock; the build runs after it.
    Held held = pools.compute(key, (k, existing) -> {
      Held h = existing != null ? existing : new Held(() -> buildPool(url, user, password, dialect));
      h.askedAtNanos = nowNanos;
      return h;
    });
    try {
      return held.pool();
    } catch (RuntimeException | Error e) {
      if (held.failed()) pools.remove(key, held);   // the next ask builds again
      throw e;
    }
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
    // Below the maximum, or idleTimeout retires nothing: at the default (= the maximum) a quiet pool would hold
    // its five sessions on the customer's database for ever. The price is a connect after a pause.
    cfg.setMinimumIdle(0);
    cfg.setIdleTimeout(IDLE_TIMEOUT_MS);
    cfg.setConnectionTimeout(5_000L);
    cfg.setPoolName("jdbc-src-" + Integer.toHexString(url.hashCode()) + "-" + user);
    return new HikariDataSource(cfg);
  }

  /** Returns the number of cached pools. Package-visible for testing. */
  int poolCount() { return pools.size(); }

  @Scheduled(every = "1m", concurrentExecution = SKIP)
  void tick() { closeUnused(System.nanoTime()); }

  /**
   * Closes every pool nobody asked for since {@link #UNUSED_POOL_TTL_NANOS} that lends no connection. The
   * closing runs after the map's lock is released: it waits on the pool's own threads.
   */
  void closeUnused(long nowNanos) {
    List<HikariDataSource> unused = new ArrayList<>();
    for (Key key : pools.keySet()) {
      pools.computeIfPresent(key, (k, held) -> {
        if (!held.unusedAt(nowNanos)) return held;
        unused.add(held.build.resultNow());
        return null;
      });
    }
    unused.forEach(HikariDataSource::close);
  }

  public void evictAll() {
    pools.values().forEach(Held::closeIfBuilt);
    pools.clear();
  }

  @PreDestroy
  void shutdown() { evictAll(); }
}

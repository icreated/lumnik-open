package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pool registry's lifecycle, on a real Hikari over a stub driver: no database, so what is pinned here is
 * what the registry decides — which pool answers a password, when a pool is let go — not what a server does
 * ({@code HikariPoolRegistryIT} has the server). Time is a parameter: the asks and the sweep sit on one
 * timeline the test writes, nothing sleeps.
 */
class HikariPoolRegistryTest {

  private static final String URL = "jdbc:stub:src";
  private static final long TTL = HikariPoolRegistry.UNUSED_POOL_TTL_NANOS;
  private static final long T0 = 1_000_000_000L;

  /** PostgreSQL's dialect (no session init), pointed at the stub driver. */
  private static final PostgresDialect STUB = new PostgresDialect() {
    @Override public String driverClass() { return StubDriver.class.getName(); }
  };

  /** Opening a connection to this url waits for {@link #gate}; the one to {@link #FAILING_URL} is refused. */
  private static final String SLOW_URL = "jdbc:stub:slow";
  private static final String FAILING_URL = "jdbc:stub:down";
  private static volatile CountDownLatch gate = new CountDownLatch(0);
  private static volatile CountDownLatch connecting = new CountDownLatch(1);

  private static final List<String> PASSWORDS_SEEN = new CopyOnWriteArrayList<>();

  private final HikariPoolRegistry registry = new HikariPoolRegistry();
  // Its own threads: the common pool is shared with the whole suite, and a test that blocks on a latch needs its thread.
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  @BeforeEach
  void forgetTheLastTest() {
    PASSWORDS_SEEN.clear();
    gate = new CountDownLatch(0);
    connecting = new CountDownLatch(1);
  }

  @AfterEach
  void evictAll() {
    registry.evictAll();
    threads.shutdownNow();
  }

  @Test
  void the_same_password_is_the_same_pool() {
    HikariDataSource first = registry.acquire(URL, "u", "pw", 1L, STUB, T0);
    HikariDataSource again = registry.acquire(URL, "u", "pw", 1L, STUB, T0 + 1);
    assertSame(first, again);
    assertEquals(1, registry.poolCount());
  }

  /**
   * The secret is resolved on every ask, so a rotation reaches {@code acquire}. The pool built with the old
   * password must not answer it: it keeps its open sessions, then fails to open new ones, until a restart.
   */
  @Test
  void a_rotated_password_opens_its_connections_with_the_new_one() throws Exception {
    HikariDataSource before = registry.acquire(URL, "u", "old", 1L, STUB, T0);
    before.getConnection().close();
    before.getHikariPoolMXBean().softEvictConnections();   // the server ended the old sessions

    HikariDataSource after = registry.acquire(URL, "u", "new", 1L, STUB, T0 + 1);
    after.getConnection().close();

    assertEquals("new", PASSWORDS_SEEN.getLast(), "the last connection opened carries the rotated password");
    assertNotSame(before, after);
  }

  @Test
  void the_pool_of_a_replaced_password_is_closed_once_nobody_asks_for_it() {
    HikariDataSource old = registry.acquire(URL, "u", "old", 1L, STUB, T0);
    HikariDataSource fresh = registry.acquire(URL, "u", "new", 1L, STUB, T0 + TTL / 2);

    registry.closeUnused(T0 + TTL);

    assertTrue(old.isClosed());
    assertFalse(fresh.isClosed());
    assertEquals(1, registry.poolCount());
  }

  @Test
  void a_pool_nobody_asked_for_in_the_limit_is_closed_by_the_sweep() {
    HikariDataSource pool = registry.acquire(URL, "u", "pw", 1L, STUB, T0);

    registry.closeUnused(T0 + TTL - 1);
    assertFalse(pool.isClosed(), "one tick short of the limit");
    assertEquals(1, registry.poolCount());

    registry.closeUnused(T0 + TTL);
    assertTrue(pool.isClosed());
    assertEquals(0, registry.poolCount());
  }

  @Test
  void asking_again_restarts_the_clock() {
    HikariDataSource pool = registry.acquire(URL, "u", "pw", 1L, STUB, T0);
    registry.acquire(URL, "u", "pw", 1L, STUB, T0 + TTL - 1);

    registry.closeUnused(T0 + TTL + 10);
    assertFalse(pool.isClosed(), "asked again 11 ticks ago");

    registry.closeUnused(T0 + TTL - 1 + TTL);
    assertTrue(pool.isClosed());
  }

  /** Closing a pool takes the connections still borrowed with it, so a query running for hours is not "unused". */
  @Test
  void a_pool_with_a_borrowed_connection_is_not_closed() throws Exception {
    HikariDataSource pool = registry.acquire(URL, "u", "pw", 1L, STUB, T0);

    try (Connection busy = pool.getConnection()) {
      registry.closeUnused(T0 + 2 * TTL);

      assertFalse(pool.isClosed());
      assertEquals(1, registry.poolCount());
      assertTrue(busy.isValid(1), "the borrowed connection was not cut");
    }

    registry.closeUnused(T0 + 2 * TTL);
    assertTrue(pool.isClosed(), "returned, the pool is unused like any other");
  }

  /**
   * Hikari applies {@code idleTimeout} only below the maximum: at its default minimum (= the maximum) a quiet
   * pool would hold its sessions on the customer's database for ever.
   */
  @Test
  void a_quiet_pool_holds_no_session() {
    HikariDataSource pool = registry.acquire(URL, "u", "pw", 1L, STUB, T0);
    assertEquals(0, pool.getMinimumIdle());
  }

  @Test
  void a_key_never_prints_the_password() {
    String shown = new HikariPoolRegistry.Key(URL, "alice", 7L, "d", "x", "s3cret").toString();
    assertFalse(shown.contains("s3cret"), shown);
    assertTrue(shown.contains("alice"), shown);
  }

  @Test
  void evictAll_closes_every_pool() {
    HikariDataSource a = registry.acquire(URL, "u1", "pw", 1L, STUB, T0);
    HikariDataSource b = registry.acquire(URL, "u2", "pw", 1L, STUB, T0);

    registry.evictAll();

    assertTrue(a.isClosed());
    assertTrue(b.isClosed());
    assertEquals(0, registry.poolCount());
  }

  // ── a build is slow or fails: the map's lock is not held while it runs ─────────────────────────

  /**
   * A map holds one lock per bin: a build under it holds every key of its bin. The neighbour is found by the
   * map's own arithmetic (spread hash, table of 16), so the test does not hope for a collision.
   */
  @Test
  void a_build_in_progress_does_not_hold_the_pools_of_its_bin() throws Exception {
    String neighbour = userInTheBinOf(SLOW_URL, "slow");
    registry.acquire(URL, neighbour, "pw", 1L, STUB, T0);
    gate = new CountDownLatch(1);
    CompletableFuture<HikariDataSource> building = CompletableFuture.supplyAsync(
        () -> registry.acquire(SLOW_URL, "slow", "pw", 1L, STUB, T0), threads);
    try {
      assertTrue(connecting.await(5, TimeUnit.SECONDS), "the build reached the driver");

      // TimeoutException: the ask, or the sweep, sat behind the build
      CompletableFuture.supplyAsync(() -> registry.acquire(URL, neighbour, "pw", 1L, STUB, T0 + 1), threads)
          .get(2, TimeUnit.SECONDS);
      CompletableFuture.runAsync(() -> registry.closeUnused(T0 + 2), threads).get(2, TimeUnit.SECONDS);
    } finally {
      gate.countDown();
    }
    assertFalse(building.get(5, TimeUnit.SECONDS).isClosed());
  }

  /** A user whose key (same table of 16) lands in the bin of {@code url}/{@code user}'s. */
  private static String userInTheBinOf(String url, String user) {
    int wanted = binOf(url, user);
    for (int i = 0; ; i++) {
      String candidate = "n" + i;
      if (binOf(URL, candidate) == wanted) return candidate;
    }
  }

  private static int binOf(String url, String user) {
    int h = new HikariPoolRegistry.Key(url, user, 1L, STUB.driverClass(), STUB.id(), "pw").hashCode();
    return (h ^ (h >>> 16)) & 15;
  }

  @Test
  void callers_of_one_key_during_its_build_get_the_one_pool() throws Exception {
    gate = new CountDownLatch(1);
    List<CompletableFuture<HikariDataSource>> asks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      asks.add(CompletableFuture.supplyAsync(() -> registry.acquire(SLOW_URL, "u", "pw", 1L, STUB, T0), threads));
    }
    assertTrue(connecting.await(5, TimeUnit.SECONDS));
    gate.countDown();

    HikariDataSource first = asks.get(0).get(5, TimeUnit.SECONDS);
    for (CompletableFuture<HikariDataSource> ask : asks) assertSame(first, ask.get(5, TimeUnit.SECONDS));
    assertEquals(1, registry.poolCount());
  }

  /** A caller who stops waiting has not failed the build: the entry stays, and the next ask gets that pool. */
  @Test
  void a_caller_interrupted_while_waiting_does_not_orphan_the_pool_being_built() throws Exception {
    gate = new CountDownLatch(1);
    CompletableFuture<HikariDataSource> builder = CompletableFuture.supplyAsync(
        () -> registry.acquire(SLOW_URL, "u", "pw", 1L, STUB, T0), threads);
    assertTrue(connecting.await(5, TimeUnit.SECONDS));

    Throwable[] thrown = new Throwable[1];
    Thread waiter = new Thread(() -> {
      try {
        registry.acquire(SLOW_URL, "u", "pw", 1L, STUB, T0 + 1);
      } catch (RuntimeException e) {
        thrown[0] = e;
      }
    });
    waiter.start();
    long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < giveUp) Thread.onSpinWait();
    waiter.interrupt();
    waiter.join(5_000);
    assertInstanceOf(IllegalStateException.class, thrown[0]);

    gate.countDown();
    HikariDataSource pool = builder.get(5, TimeUnit.SECONDS);
    assertSame(pool, registry.acquire(SLOW_URL, "u", "pw", 1L, STUB, T0 + 2), "no second pool, no orphan");
    assertEquals(1, registry.poolCount());
  }

  @Test
  void a_build_that_fails_leaves_no_pool_behind_and_the_next_ask_builds_again() {
    assertThrows(RuntimeException.class, () -> registry.acquire(FAILING_URL, "u", "pw", 1L, STUB, T0));
    assertEquals(0, registry.poolCount());

    assertThrows(RuntimeException.class, () -> registry.acquire(FAILING_URL, "u", "pw", 1L, STUB, T0 + 1));
    assertEquals(0, registry.poolCount());
  }

  // ── the stub ────────────────────────────────────────────────────────────────────────────────────

  /** Public with a public constructor: Hikari instantiates it by name. */
  public static final class StubDriver implements Driver {
    @Override public Connection connect(String url, Properties info) throws SQLException {
      if (url.equals(FAILING_URL)) throw new SQLException("connection refused");
      if (url.equals(SLOW_URL)) {
        connecting.countDown();
        try {
          gate.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLException(e);
        }
      }
      PASSWORDS_SEEN.add(info.getProperty("password"));
      return connection();
    }
    @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:stub:"); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }
  }

  /** A connection that is valid until it is closed or aborted, and answers everything else with a zero. */
  private static Connection connection() {
    boolean[] ended = {false};
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "isValid" -> !ended[0];
          case "isClosed" -> ended[0];
          case "close", "abort" -> { ended[0] = true; yield null; }
          case "getAutoCommit" -> true;
          case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
          case "isWrapperFor" -> false;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "stub connection";
          default -> zero(method.getReturnType());
        });
  }

  private static Object zero(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }
}

package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.api.types.RunScope;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The engine's {@link RunScope}: one per endpoint run, created by {@code ConnectorEngine.runChunks} before the
 * run start is measured and closed in its {@code finally}. On the normal endings the terminal status is persisted by
 * then; when something unexpected escapes the run, this closes first and the row is finalized (or left to the
 * reaper) afterwards. {@link ChunkExecutor}'s five-argument overloads, which have no run around them, open one for the
 * span of a single call.
 *
 * <p>{@link #close} runs the resources newest-first and logs whatever one throws and goes on: a transport that will
 * not close must not rewrite how the run ended. A {@link VirtualMachineError} is the one thing it lets out, after every
 * other resource got its close; further ones ride along as suppressed.
 *
 * <p>A {@link ReentrantLock}, not {@code synchronized}: the runs execute on virtual threads, and on JDK 21 a virtual
 * thread that blocks inside a synchronized block pins its carrier. A resource is built under the lock (two
 * first-callers must not build two sessions) but closed outside it, so a slow close never holds the others up.
 */
public final class OwnedRunScope implements RunScope, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(OwnedRunScope.class);

  private final Object owner;
  private final BooleanSupplier cancelRequested;
  private final ReentrantLock lock = new ReentrantLock();
  /** Insertion order is creation order: close walks it backwards. A key invalidated then rebuilt re-enters last. */
  private final Map<Object, AutoCloseable> open = new LinkedHashMap<>();
  private boolean closed;

  /** @param owner only labels the log lines — the run id */
  public OwnedRunScope(Object owner) {
    this(owner, () -> false);
  }

  /** @param cancelRequested the engine's cancel flag for this run, read by {@link #cancelRequested()} */
  public OwnedRunScope(Object owner, BooleanSupplier cancelRequested) {
    this.owner = owner;
    this.cancelRequested = Objects.requireNonNull(cancelRequested, "a run scope needs its run's cancel flag");
  }

  @Override
  public boolean cancelRequested() {
    return cancelRequested.getAsBoolean();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends AutoCloseable> T resource(Object key, Supplier<T> factory) {
    Objects.requireNonNull(key, "a run scope resource needs a key");
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("run " + owner + ": the scope is closed, resource '" + key + "' was asked for after the run ended");
      }
      AutoCloseable held = open.get(key);
      if (held != null) return (T) held;
      T built = Objects.requireNonNull(factory.get(), "the factory for run scope resource '" + key + "' returned null");
      open.put(key, built);
      return built;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidate(Object key) {
    AutoCloseable gone;
    lock.lock();
    try {
      gone = open.remove(key);
    } finally {
      lock.unlock();
    }
    if (gone != null) closeLogged(key, gone);
  }

  @Override
  public void close() {
    List<Map.Entry<Object, AutoCloseable>> entries = new ArrayList<>();
    lock.lock();
    try {
      if (closed) return;
      closed = true;
      open.forEach((key, resource) -> entries.add(Map.entry(key, resource)));
      open.clear();
    } finally {
      lock.unlock();
    }
    Error fatal = null;
    for (int i = entries.size() - 1; i >= 0; i--) {
      try {
        closeLogged(entries.get(i).getKey(), entries.get(i).getValue());
      } catch (Error e) {
        // Not swallowed (the JVM may be in no state to go on), but the resources still waiting get their close first.
        if (fatal == null) fatal = e; else fatal.addSuppressed(e);
      }
    }
    if (fatal != null) throw fatal;
  }

  /**
   * Whatever a resource throws on close is logged and forgotten, except a {@link VirtualMachineError} (out of memory,
   * stack overflow, internal error): the JVM is in no state to go on, so that one propagates to {@link #close}. A
   * {@code LinkageError} is not that: a transport whose client class cannot load must not turn a finished run into a
   * crash. An interrupt met on the way is put back on the thread, so a cancel or a shutdown is not lost.
   */
  private void closeLogged(Object key, AutoCloseable resource) {
    try {
      resource.close();
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (Throwable t) {
      if (t instanceof InterruptedException) Thread.currentThread().interrupt();
      LOG.warnf(t, "run %s: closing '%s' failed — %s: %s", owner, key, t.getClass().getSimpleName(), t.getMessage());
    }
  }
}

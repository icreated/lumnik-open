package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.RunScope;

import java.util.function.Supplier;

/** A scope that counts how many times a factory actually ran: the number of resources the run built. */
final class CountingRunScope implements RunScope {

  private final RunScope delegate;
  private int builds;

  CountingRunScope(RunScope delegate) {
    this.delegate = delegate;
  }

  int builds() {
    return builds;
  }

  @Override
  public <T extends AutoCloseable> T resource(Object key, Supplier<T> factory) {
    return delegate.resource(key, () -> {
      builds++;
      return factory.get();
    });
  }

  @Override
  public void invalidate(Object key) {
    delegate.invalidate(key);
  }
}

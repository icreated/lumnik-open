package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.RunScope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A scope that counts how many times a factory actually ran: the number of resources the run built. */
final class CountingRunScope implements RunScope {

  private final RunScope delegate;
  private final List<Object> built = new ArrayList<>();

  CountingRunScope(RunScope delegate) {
    this.delegate = delegate;
  }

  int builds() {
    return built.size();
  }

  /** How many of those resources were keyed by {@code keyType}. */
  long builds(Class<?> keyType) {
    return built.stream().filter(keyType::isInstance).count();
  }

  @Override
  public <T extends AutoCloseable> T resource(Object key, Supplier<T> factory) {
    return delegate.resource(key, () -> {
      built.add(key);
      return factory.get();
    });
  }

  @Override
  public void invalidate(Object key) {
    delegate.invalidate(key);
  }
}

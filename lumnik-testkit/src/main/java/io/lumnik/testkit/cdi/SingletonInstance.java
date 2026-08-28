package io.lumnik.testkit.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

/** A CDI Instance resolving to exactly one supplied bean — for unit tests. */
public class SingletonInstance<T> implements Instance<T> {
    private final T value;
    public SingletonInstance(T value) { this.value = value; }
    public boolean isUnsatisfied() { return false; }
    public boolean isAmbiguous() { return false; }
    public boolean isResolvable() { return true; }
    public T get() { return value; }
    public Iterator<T> iterator() { return List.of(value).iterator(); }
    public Instance<T> select(Annotation... q) { return this; }
    public <U extends T> Instance<U> select(Class<U> s, Annotation... q) { throw new UnsupportedOperationException(); }
    public <U extends T> Instance<U> select(TypeLiteral<U> s, Annotation... q) { throw new UnsupportedOperationException(); }
    public void destroy(T instance) {
        // Nothing to dispose: this double hands out a value it does not own — the test that built it does,
        // so there is no contextual instance for the container to destroy. Empty is the
        // correct implementation here, not an unfinished one.
    }
    public Instance.Handle<T> getHandle() { throw new UnsupportedOperationException(); }
    public Iterable<? extends Instance.Handle<T>> handles() { throw new UnsupportedOperationException(); }
}

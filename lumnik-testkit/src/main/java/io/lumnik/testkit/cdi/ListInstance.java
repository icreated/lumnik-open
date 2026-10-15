package io.lumnik.testkit.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

/** A CDI Instance resolving to zero or more supplied beans — for unit tests that need to prove
 *  ambiguous-resolution handling (the sibling {@link SingletonInstance} always resolves to one). */
public class ListInstance<T> implements Instance<T> {
    private final List<T> values;

    public ListInstance(List<T> values) {
        this.values = List.copyOf(values);
    }

    public boolean isUnsatisfied() { return values.isEmpty(); }
    public boolean isAmbiguous() { return values.size() > 1; }
    public boolean isResolvable() { return values.size() == 1; }

    public T get() {
        if (values.size() != 1) {
            throw new IllegalStateException(values.isEmpty() ? "unsatisfied" : "ambiguous");
        }
        return values.get(0);
    }

    public Iterator<T> iterator() { return values.iterator(); }
    public Instance<T> select(Annotation... q) { return this; }
    public <U extends T> Instance<U> select(Class<U> s, Annotation... q) { throw new UnsupportedOperationException(); }
    public <U extends T> Instance<U> select(TypeLiteral<U> s, Annotation... q) { throw new UnsupportedOperationException(); }

    public void destroy(T instance) {
        // Nothing to dispose: this double hands out values it does not own, same rationale as
        // SingletonInstance's own no-op.
    }

    public Instance.Handle<T> getHandle() { throw new UnsupportedOperationException(); }
    public Iterable<? extends Instance.Handle<T>> handles() { throw new UnsupportedOperationException(); }
}

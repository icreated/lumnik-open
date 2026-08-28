package io.lumnik.testkit.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Iterator;

/** An always-unsatisfied CDI Instance for unit tests. */
public class EmptyInstance<T> implements Instance<T> {
    public boolean isUnsatisfied() { return true; }
    public boolean isAmbiguous() { return false; }
    public boolean isResolvable() { return false; }
    public T get() { throw new IllegalStateException("unsatisfied"); }
    public Iterator<T> iterator() { return Collections.emptyIterator(); }
    public Instance<T> select(Annotation... q) { return this; }
    public <U extends T> Instance<U> select(Class<U> s, Annotation... q) { return new EmptyInstance<>(); }
    public <U extends T> Instance<U> select(TypeLiteral<U> s, Annotation... q) { return new EmptyInstance<>(); }
    public void destroy(T instance) {
        // Nothing to dispose: this double resolves nothing, so it never handed anything out,
        // so there is no contextual instance for the container to destroy. Empty is the
        // correct implementation here, not an unfinished one.
    }
    public Instance.Handle<T> getHandle() { throw new IllegalStateException(); }
    public Iterable<? extends Instance.Handle<T>> handles() { return Collections.emptyList(); }
}

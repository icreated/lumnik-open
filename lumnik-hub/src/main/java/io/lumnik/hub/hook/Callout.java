package io.lumnik.hub.hook;

public interface Callout<T> {
    CalloutResult onChange(T entity, HookContext ctx);

    @SuppressWarnings("unchecked")
    default CalloutResult invoke(Object entity, HookContext ctx) {
        return onChange((T) entity, ctx);
    }
}

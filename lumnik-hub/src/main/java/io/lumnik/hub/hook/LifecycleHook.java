package io.lumnik.hub.hook;

/**
 * Implementers override only the methods they care about.
 * All defaults are no-ops, so a hook listening on BEFORE_CREATE only
 * doesn't need to declare AFTER_CREATE, BEFORE_UPDATE, etc.
 */
public interface LifecycleHook<T> {
    default void onBeforeCreate(T entity, HookContext ctx) {}
    default void onAfterCreate(T entity, HookContext ctx) {}
    default void onBeforeUpdate(T entity, HookContext ctx) {}
    default void onAfterUpdate(T entity, HookContext ctx) {}
    default void onBeforeDelete(T entity, HookContext ctx) {}
    default void onAfterDelete(T entity, HookContext ctx) {}
    default void onBeforeArchive(T entity, HookContext ctx) {}
    default void onAfterArchive(T entity, HookContext ctx) {}

    default void onBeforePrepare(T entity, HookContext ctx) {}
    default void onAfterPrepare(T entity, HookContext ctx) {}
    default void onBeforeComplete(T entity, HookContext ctx) {}
    default void onAfterComplete(T entity, HookContext ctx) {}
    default void onBeforeVoid(T entity, HookContext ctx) {}
    default void onAfterVoid(T entity, HookContext ctx) {}
    default void onBeforeClose(T entity, HookContext ctx) {}
    default void onAfterClose(T entity, HookContext ctx) {}
    default void onBeforeReactivate(T entity, HookContext ctx) {}
    default void onAfterReactivate(T entity, HookContext ctx) {}

    /** Dispatch helper used by HookExecutor. */
    @SuppressWarnings("unchecked")
    default void dispatch(LifecycleEvent event, Object entity, HookContext ctx) {
        T e = (T) entity;
        switch (event) {
            case BEFORE_CREATE     -> onBeforeCreate(e, ctx);
            case AFTER_CREATE      -> onAfterCreate(e, ctx);
            case BEFORE_UPDATE     -> onBeforeUpdate(e, ctx);
            case AFTER_UPDATE      -> onAfterUpdate(e, ctx);
            case BEFORE_DELETE     -> onBeforeDelete(e, ctx);
            case AFTER_DELETE      -> onAfterDelete(e, ctx);
            case BEFORE_ARCHIVE    -> onBeforeArchive(e, ctx);
            case AFTER_ARCHIVE     -> onAfterArchive(e, ctx);
            case BEFORE_PREPARE    -> onBeforePrepare(e, ctx);
            case AFTER_PREPARE     -> onAfterPrepare(e, ctx);
            case BEFORE_COMPLETE   -> onBeforeComplete(e, ctx);
            case AFTER_COMPLETE    -> onAfterComplete(e, ctx);
            case BEFORE_VOID       -> onBeforeVoid(e, ctx);
            case AFTER_VOID        -> onAfterVoid(e, ctx);
            case BEFORE_CLOSE      -> onBeforeClose(e, ctx);
            case AFTER_CLOSE       -> onAfterClose(e, ctx);
            case BEFORE_REACTIVATE -> onBeforeReactivate(e, ctx);
            case AFTER_REACTIVATE  -> onAfterReactivate(e, ctx);
        }
    }
}

package io.lumnik.hub.hook.kts

/**
 * Lightweight DSL helpers for Kotlin hook scripts.
 *
 * Scripts receive hub types (LifecycleEvent, HookContext) through the `bindings` property.
 * This module has no compile-time dependency on lumnik-hub to avoid a circular
 * Maven dependency; hub types are resolved at runtime from the classloader.
 *
 * Usage in a .kts script:
 *   import io.lumnik.hub.hook.LifecycleEvent
 *   import io.lumnik.hub.hook.HookContext
 *   val event = bindings["event"] as LifecycleEvent
 *   val ctx   = bindings["ctx"]   as HookContext
 */

/** Returns a typed binding value or null if absent or wrong type. */
inline fun <reified T> javax.script.Bindings.typed(key: String): T? =
    get(key) as? T

/** Throws IllegalStateException with a descriptive message if key is missing. */
inline fun <reified T> javax.script.Bindings.require(key: String): T =
    typed<T>(key) ?: error("Hook binding '$key' of type ${T::class.simpleName} not found")

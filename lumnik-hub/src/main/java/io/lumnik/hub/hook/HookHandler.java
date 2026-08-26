package io.lumnik.hub.hook;

public sealed interface HookHandler {
    record JavaHandler(String className) implements HookHandler {}
    record KotlinHandler(String scriptPath) implements HookHandler {}
}

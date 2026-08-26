package io.lumnik.hub.hook;

/** Implementers handle a named action (e.g. invoice.send). */
public interface ActionHandler {
    void handle(Object payload, HookContext ctx);
}

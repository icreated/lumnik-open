package io.lumnik.hub.hook;

public record CalloutBinding(
    String id,
    String module,
    String description,
    String entityClassName,
    String field,
    HookHandler handler
) {}

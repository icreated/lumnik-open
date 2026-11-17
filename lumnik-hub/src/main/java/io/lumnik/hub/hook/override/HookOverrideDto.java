package io.lumnik.hub.hook.override;

/** A tenant's override of one hook: whether it runs for that tenant. No code since #2011. */
public record HookOverrideDto(String hookId, Long tenantId, boolean enabled) {}

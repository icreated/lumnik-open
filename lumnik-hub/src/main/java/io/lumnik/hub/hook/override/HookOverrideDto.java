package io.lumnik.hub.hook.override;

public record HookOverrideDto(String hookId, Long tenantId, String script, boolean enabled) {}

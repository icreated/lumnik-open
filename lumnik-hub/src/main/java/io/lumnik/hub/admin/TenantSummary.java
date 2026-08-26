package io.lumnik.hub.admin;

public record TenantSummary(
    Long id,
    String code,
    String name,
    long orgCount,
    long userCount
) {}

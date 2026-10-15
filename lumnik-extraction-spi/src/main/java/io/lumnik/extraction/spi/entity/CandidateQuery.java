package io.lumnik.extraction.spi.entity;

import java.util.Map;

/** What a {@code propose:} rule sends a provider — the row it is trying to resolve, and nothing else. */
public record CandidateQuery(long tenantId, String entityType, String ruleDeclaredId, Map<String, String> rawFieldValues) {}

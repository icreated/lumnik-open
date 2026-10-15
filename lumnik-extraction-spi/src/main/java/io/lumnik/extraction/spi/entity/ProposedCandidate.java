package io.lumnik.extraction.spi.entity;

import java.util.UUID;

/** One candidate a provider proposes — never a decision, only a suggestion the hub still scores. */
public record ProposedCandidate(UUID entityId, double score) {}

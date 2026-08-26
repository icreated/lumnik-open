package io.lumnik.hub.fidelity;

/** A single honesty violation surfaced by an oracle on a generated scenario. */
public record Finding(Family family, Severity severity, long seed, String detail) {}

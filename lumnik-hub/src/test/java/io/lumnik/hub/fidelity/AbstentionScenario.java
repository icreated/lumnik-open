package io.lumnik.hub.fidelity;

/** A generated abstention probe: the kind, the model's SQL-gen reply (unused for EMPTY_SCHEMA, where the
 *  model is never called), and whether the system must abstain. */
public record AbstentionScenario(long seed, AbstentionKind kind, String modelReply, boolean expectAbstain) {}

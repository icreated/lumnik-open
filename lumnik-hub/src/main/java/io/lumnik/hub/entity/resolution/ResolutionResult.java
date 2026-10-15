package io.lumnik.hub.entity.resolution;

import java.util.List;
import java.util.UUID;

/**
 * The decision {@link CascadeResolver#resolve} reached for one source row, plus the observability
 * facts (#622) a caller cannot otherwise reconstruct from {@code state}/{@code entityId} alone: a
 * fresh mint and a 'same'-assertion override both land as {@code RESOLVED} with no rule attached,
 * so {@code newlyMinted} says which one actually happened; {@code providerCalls}/{@code
 * providerFailures} count real calls to a wired {@code CandidateProvider}, never "no bean wired at
 * all"; {@code ruleIdsInvolved} is the distinct, non-null rule ids of every ledger event this call
 * wrote — a rule that never fires and one that fires on everything are both visible from the tally
 * a whole pass folds these into.
 */
public record ResolutionResult(EntityResolutionState state, UUID entityId, boolean newlyMinted,
    int providerCalls, int providerFailures, List<String> ruleIdsInvolved) {}

package io.lumnik.testkit.search.contract;

/** Mirrors {@code RebuildClaim}'s five acceptance variants exactly (SearchOperationsResource.java) —
 *  this is what {@code POST /api/platform/search/{name}/rebuild} accepted or refused, never a claim
 *  about whether the rebuild has finished (see {@link RebuildTerminalOutcome}). */
public sealed interface RebuildOutcome {
    record Started(String runId) implements RebuildOutcome {}
    record RebuildAlreadyRunning(String runId) implements RebuildOutcome {}
    record PairBusy() implements RebuildOutcome {}
    record DeclarationChanged() implements RebuildOutcome {}
    record TooManyInFlight() implements RebuildOutcome {}
}

package io.lumnik.testkit.search.contract;

/** A fully provisioned "resolution merge" scenario for {@code CHANGE_FEED_TRIGGERS} (#701, PR #734
 *  review round 4): {@code entity} is search-indexed and already carries two independently-resolved,
 *  observable rows ({@code intoRowKey}/{@code fromRowKey}), ready to be merged via {@link
 *  HubSearchContractDriver#mergeResolvableScenario} and observed via {@link
 *  HubSearchContractDriver#query}. Every provider-specific detail of how a driver models
 *  resolution/crosswalk internally stays behind {@link
 *  HubSearchContractDriver#prepareResolvableMergeScenario} — the harness only ever sees these four
 *  fields, never SQL, a crosswalk table, or an assertion API. */
public record ResolvableMergeScenario(CanonicalEntityDef entity, UserHandle user, String intoRowKey, String fromRowKey) {}

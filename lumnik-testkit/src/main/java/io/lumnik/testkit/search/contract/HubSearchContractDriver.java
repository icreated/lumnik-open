package io.lumnik.testkit.search.contract;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Neutral driver behind every hub-lane dimension that needs real canonical data, a scoped user, or
 *  the real HTTP surface — {@code lumnik-testkit} cannot call {@code SearchGate}, any hub HTTP
 *  resource, or the projection worker directly, so this interface carries only testkit-owned DTOs
 *  and {@code io.lumnik.extraction.spi.search} types. Exactly one real implementation exists
 *  ({@code RestHubSearchContractDriver}, in {@code lumnik-hub}'s own test tree). */
public interface HubSearchContractDriver {

    // Provisioning
    TenantHandle provisionTenant();
    UserHandle provisionUser(TenantHandle tenant, Set<String> scopeTags);
    void grantScope(TenantHandle tenant, UserHandle user, String scopeTag);
    void revokeScope(TenantHandle tenant, UserHandle user, String scopeTag);
    void ingestCanonicalRows(TenantHandle tenant, CanonicalEntityDef entity, List<CanonicalRow> rows);
    void deleteCanonicalRow(TenantHandle tenant, CanonicalEntityDef entity, String rowKey);
    void applySearchIndex(TenantHandle tenant, String indexName, CanonicalEntityDef entity, List<CanonicalFieldDef> fields);

    // Change-feed triggers (#701)
    void reapplyEntity(TenantHandle tenant, CanonicalEntityDef updated);
    /** Provisions a search-indexed entity with two independently-resolved rows, ready to be merged
     *  via {@link #mergeResolvableScenario} (PR #734 review round 4) — the harness never touches how
     *  a driver models resolution/crosswalk internally, only this scenario's own entity/user/row
     *  keys before and after triggering the merge. {@code probeText} is the value both rows'
     *  searchable canonical field carries, so the caller's own query text finds them. */
    ResolvableMergeScenario prepareResolvableMergeScenario(TenantHandle tenant, String indexName, String probeText);
    /** The genuine crosswalk-driven merge for a {@link #prepareResolvableMergeScenario}-built
     *  scenario — its own method (PR #734 review round 6), not inferred by driver-private
     *  bookkeeping. */
    void mergeResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario);
    /** The genuine crosswalk-driven split for a {@link #prepareResolvableMergeScenario}-built
     *  scenario, already converged via {@link #mergeResolvableScenario} — retracts the same
     *  assertion that produced the merge (icreated/lumnik#737) and reprocesses the same "from" row
     *  {@link #mergeResolvableScenario} itself moved. */
    void splitResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario);
    // triggerResolutionMerge (the rowKey-rewrite diagnostic) and deleteConnector removed from this
    // contract (PR #734 review rounds 6-7): triggerResolutionMerge never performed a real resolution
    // merge (mergeResolvableScenario, above, does), so keeping it public was the same false
    // guarantee round 6 already removed for split/deleteConnector — its narrower
    // change-feed-observability diagnostic lives on as RestHubSearchContractDriver's own
    // package-visible rewriteMatchKey, exercised only by the dedicated
    // RestHubSearchContractDriverChangeFeedIT. triggerResolutionSplit itself is reintroduced above as
    // splitResolvableScenario, with real semantics (icreated/lumnik#737) — deleteConnector
    // (icreated/lumnik#736) is unaffected and stays removed; full rationale in the design spec's
    // CHANGE_FEED_TRIGGERS section.
    void deleteTenant(TenantHandle tenant);
    void awaitProjectionConvergence(TenantHandle tenant, String indexName, Duration timeout);
    /** Runs exactly ONE projection-worker cycle — no retry loop, no timeout (PR #734 review round 5):
     *  {@link #awaitProjectionConvergence}'s own defensive retry-until-timeout loop can never actually
     *  falsify design spec §8's "reflected in the index within one worker cycle" claim, since a
     *  trigger needing several cycles would still pass by exhausting the wait. Reserved for triggers
     *  whose convergence is genuinely incremental (never routed through a rebuild fallback) — a
     *  trigger that IS a documented rebuild-based exception (e.g. a hard-delete gap) keeps using
     *  {@link #awaitProjectionConvergence} instead, and must say so where it's tested. */
    void awaitOneProjectionCycle(TenantHandle tenant, String indexName);

    // Query and rebuild — through the real HTTP surface, never SearchGate directly
    CanonicalSearchResult query(TenantHandle tenant, UserHandle asUser, String text, Map<String, List<String>> facets);
    RebuildOutcome triggerRebuild(TenantHandle tenant, String indexName);
    RebuildTerminalOutcome awaitRebuildCompletion(TenantHandle tenant, String indexName,
                                                   RebuildOutcome triggered, Duration timeout);
}

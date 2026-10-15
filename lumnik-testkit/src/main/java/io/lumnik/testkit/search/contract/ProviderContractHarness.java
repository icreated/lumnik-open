package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchException;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.InvalidIndexedSearchQueryException;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionField;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.extraction.spi.search.SearchAvailability;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The provider-neutral acceptance suite every {@code IndexedSearchPort}/{@code ProjectionSink}
 *  provider must pass (#674). Owns every assertion; a provider module supplies only environment
 *  ({@link ProviderContractFixture}) and fault mechanism — it never reinterprets what a passing
 *  result means. */
public final class ProviderContractHarness {

    private static final String INDEX = "contract-suite-isolation";
    private static final String ENTITY = "contract_suite_probe";
    // #674 finding: allowlistEnforcementTests and changeFeedTriggerTests' column-drop sub-test both
    // need a SECOND canonical column ("secret") on the same entity name — against a real hub-lane
    // driver (not the fake, which tracks rows by rowKey rather than by physical table shape), a
    // later, wider CanonicalEntityDef sharing ENTITY's name collides with the narrower probeEntity()
    // shape every OTHER dimension already applied against the same physical backing table. Safe to
    // share this second name between exactly these two: hubLane()'s dispatch order runs
    // ALLOWLIST_ENFORCEMENT to full completion before CHANGE_FEED_TRIGGERS starts, and each test
    // provisions its own tenant, so there is no interleaving and no cross-tenant contamination.
    private static final String ENTITY_WITH_SECRET = "contract_suite_probe_with_secret";
    private static final String PROBE_TEXT = "contractsuiteprobe";
    private static final String HUB_INDEX = "contract-suite-hub-isolation";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ProviderContractHarness() {}

    public static Stream<DynamicNode> providerLane(ProviderContractFixture fixture) {
        return Stream.of(
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.ISOLATION,
                () -> isolationTests(fixture)),
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.LIFECYCLE_CUTOVER,
                () -> lifecycleCutoverTests(fixture)),
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.BOOT_ABSENT,
                () -> bootAbsentTests(fixture)),
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.BACKEND_LOST_DURING_QUERY,
                () -> backendLostDuringQueryTests(fixture)),
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.BACKEND_LOST_DURING_INDEXING,
                () -> backendLostDuringIndexingTests(fixture)),
            dimensionOrAbort(fixture.capabilities(), ProviderLaneDimension.QUERY_ABUSE,
                () -> queryAbuseTests(fixture))
        );
    }

    private static DynamicNode dimensionOrAbort(ProviderCapabilities capabilities, ProviderLaneDimension dim,
                                                 Supplier<List<DynamicTest>> tests) {
        Applicability applicability = capabilities.applicability(dim);
        if (applicability instanceof Applicability.NotApplicable na) {
            return DynamicContainer.dynamicContainer(dim.name(), List.of(
                DynamicTest.dynamicTest("not applicable", () -> Assumptions.abort(na.reason()))));
        }
        return DynamicContainer.dynamicContainer(dim.name(), tests.get());
    }

    private static List<DynamicTest> isolationTests(ProviderContractFixture fixture) {
        return List.of(
            DynamicTest.dynamicTest("cross-tenant query never returns the other tenant's hits", () -> {
                long tenantA = 900_001, tenantB = 900_002;
                seedPublishedGeneration(fixture, tenantA, List.of(probeDoc("a-1")));
                seedPublishedGeneration(fixture, tenantB, List.of(probeDoc("b-1")));

                IndexedSearchResult resultA = fixture.provider().search(probeQuery(tenantA, Set.of(ENTITY)));
                assertThat(resultA.hits()).extracting(h -> h.key().get("id")).containsExactly("a-1");

                IndexedSearchResult resultB = fixture.provider().search(probeQuery(tenantB, Set.of(ENTITY)));
                assertThat(resultB.hits()).extracting(h -> h.key().get("id")).containsExactly("b-1");
            }),
            DynamicTest.dynamicTest("a query outside allowedEntities returns zero hits for that entity", () -> {
                long tenant = 900_003;
                seedPublishedGeneration(fixture, tenant, List.of(probeDoc("c-1")));

                IndexedSearchResult result = fixture.provider().search(probeQuery(tenant, Set.of("other-entity")));
                assertThat(result.hits()).isEmpty();
            })
        );
    }

    private static List<DynamicTest> lifecycleCutoverTests(ProviderContractFixture fixture) {
        return List.of(
            DynamicTest.dynamicTest("a concurrent search never observes a half-published generation", () -> {
                long tenant = 900_201;
                String gen1 = fixture.sink().beginGeneration(tenant, INDEX, definition());
                fixture.sink().upsert(tenant, INDEX, gen1, List.of(probeDoc("old-1")));
                fixture.sink().publish(tenant, INDEX, gen1);

                String gen2 = fixture.sink().beginGeneration(tenant, INDEX, definition());
                fixture.sink().upsert(tenant, INDEX, gen2, List.of(probeDoc("new-1"), probeDoc("new-2")));

                AtomicBoolean mixedObservationSeen = new AtomicBoolean(false);
                AtomicBoolean keepGoing = new AtomicBoolean(true);
                AtomicReference<Throwable> readerFailure = new AtomicReference<>();
                AtomicInteger iterations = new AtomicInteger(0);
                CountDownLatch firstIterationDone = new CountDownLatch(1);
                Thread reader = new Thread(() -> {
                    while (keepGoing.get()) {
                        try {
                            Set<String> ids = fixture.provider().search(probeQuery(tenant, Set.of(ENTITY)))
                                .hits().stream().map(h -> h.key().get("id"))
                                .collect(Collectors.toSet());
                            boolean isOldOnly = ids.equals(Set.of("old-1"));
                            boolean isNewOnly = ids.equals(Set.of("new-1", "new-2"));
                            if (!isOldOnly && !isNewOnly) mixedObservationSeen.set(true);
                            iterations.incrementAndGet();
                            firstIterationDone.countDown();
                        } catch (Throwable t) {
                            // A crashed reader must not die silently and keep the test looking green
                            // for the rest of the race window — capture it and stop looping.
                            readerFailure.set(t);
                            firstIterationDone.countDown();
                            break;
                        }
                    }
                });
                reader.start();
                // Guarantees the reader has genuinely observed at least one state before the cutover
                // fires — otherwise, against a fast enough sink, publish() can complete (and
                // keepGoing flip false) before the OS ever schedules the reader's first iteration,
                // making the race this test exists to exercise never actually happen.
                firstIterationDone.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                try {
                    fixture.sink().publish(tenant, INDEX, gen2);
                } finally {
                    // A publish() failure must still stop the (non-daemon) reader thread — otherwise
                    // it spins forever against the database instead of the test failing outright.
                    keepGoing.set(false);
                }
                // Bounded, not reader.join(): a provider.search() call hung mid-iteration (e.g. a
                // stuck DB connection) would otherwise hang this test forever instead of failing it.
                reader.join(TIMEOUT.toMillis());
                if (reader.isAlive()) {
                    reader.interrupt();
                    throw new AssertionError("reader thread did not stop within " + TIMEOUT);
                }

                if (readerFailure.get() != null) {
                    throw new AssertionError("reader thread failed", readerFailure.get());
                }
                assertThat(iterations.get()).as("the reader must have observed at least one state").isGreaterThanOrEqualTo(1);
                assertThat(mixedObservationSeen).isFalse();
            }),
            DynamicTest.dynamicTest("a failed upsert before publish leaves the prior live generation untouched", () -> {
                long tenant = 900_202;
                seedPublishedGeneration(fixture, tenant, List.of(probeDoc("live-1")));

                String gen2 = fixture.sink().beginGeneration(tenant, INDEX, definition());
                try (AutoCloseable fault = fixture.faults().loseBackendDuringIndexing()) {
                    assertThatThrownBy(() -> fixture.sink().upsert(tenant, INDEX, gen2, List.of(probeDoc("staging-1"))))
                        .isInstanceOf(IllegalStateException.class);
                }

                IndexedSearchResult result = fixture.provider().search(probeQuery(tenant, Set.of(ENTITY)));
                assertThat(result.hits()).extracting(h -> h.key().get("id")).containsExactly("live-1");
            })
        );
    }

    private static List<DynamicTest> bootAbsentTests(ProviderContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "a provider booted with its backend absent reports a coherent, non-AVAILABLE status", () -> {
                var freshProvider = fixture.faults().bootWithBackendAbsent();
                assertThat(freshProvider.status().state()).isNotEqualTo(SearchAvailability.AVAILABLE);
            }));
    }

    private static List<DynamicTest> backendLostDuringQueryTests(ProviderContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "status flips to DEGRADED, search() throws IndexedSearchException, then both recover", () -> {
                long tenant = 900_301;
                seedPublishedGeneration(fixture, tenant, List.of(probeDoc("q-1")));

                assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.AVAILABLE);
                assertThat(fixture.provider().search(probeQuery(tenant, Set.of(ENTITY))).hits()).isNotEmpty();

                try (AutoCloseable fault = fixture.faults().loseBackendDuringQuery()) {
                    assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.DEGRADED);
                    assertThatThrownBy(() -> fixture.provider().search(probeQuery(tenant, Set.of(ENTITY))))
                        .isInstanceOf(IndexedSearchException.class);
                }

                assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.AVAILABLE);
                assertThat(fixture.provider().search(probeQuery(tenant, Set.of(ENTITY))).hits()).isNotEmpty();
            }));
    }

    private static List<DynamicTest> backendLostDuringIndexingTests(ProviderContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "status flips to DEGRADED, an indexing fault throws IllegalStateException, then both recover", () -> {
                long tenant = 900_302;
                assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.AVAILABLE);

                try (AutoCloseable fault = fixture.faults().loseBackendDuringIndexing()) {
                    assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.DEGRADED);
                    assertThatThrownBy(() -> fixture.sink().beginGeneration(tenant, INDEX, definition()))
                        .isInstanceOf(IllegalStateException.class);
                }

                assertThat(fixture.provider().status().state()).isEqualTo(SearchAvailability.AVAILABLE);
                seedPublishedGeneration(fixture, tenant, List.of(probeDoc("recovered-1")));
                assertThat(fixture.provider().search(probeQuery(tenant, Set.of(ENTITY))).hits()).isNotEmpty();
            }));
    }

    private static List<DynamicTest> queryAbuseTests(ProviderContractFixture fixture) {
        long tenant = 900_101;
        // Real, published data with two documents whose probe text genuinely differs — a hostile
        // query that matched everything indiscriminately (or slipped past isolation) would be
        // caught here; against an empty tenant every hostile query trivially "passes" by never
        // reaching real matching at all (#674 finding).
        seedPublishedGeneration(fixture, tenant, List.of(
            probeDocWithText("abuse-1", PROBE_TEXT),
            probeDocWithText("abuse-2", "utterlyunrelatedcontent")));

        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest("oversize input text never matches indiscriminately, nor throws unwrapped", () ->
            assertHostileQueryNeverMatchesIndiscriminately(fixture, tenant, "a".repeat(50_000), Map.of())));
        tests.add(DynamicTest.dynamicTest("a filter literally named tenant_id is never an escape hatch, nor throws unwrapped", () ->
            assertHostileQueryNeverMatchesIndiscriminately(fixture, tenant, PROBE_TEXT, Map.of("tenant_id", "1"))));
        for (String hostile : fixture.providerNativeHostileQueries()) {
            tests.add(DynamicTest.dynamicTest("provider-native hostile text never matches indiscriminately, nor throws unwrapped: " + hostile, () ->
                assertHostileQueryNeverMatchesIndiscriminately(fixture, tenant, hostile, Map.of())));
        }
        return tests;
    }

    /** Runs a hostile query and asserts it never matches BOTH seeded probe rows at once (the
     *  "never matches indiscriminately" claim this dimension actually requires) — while accepting
     *  either no exception, or a legitimate SPI-level refusal ({@link InvalidIndexedSearchQueryException},
     *  "I understood this was bad and refused") as a pass. The broader {@link IndexedSearchException}
     *  also covers a genuine provider crash (PR #734 review finding: catching it here would make the
     *  "never throws unwrapped" half of this dimension vacuous) — only its narrower subclass is an
     *  honest refusal, so only it is swallowed; every other exception, including a bare
     *  IndexedSearchException, fails this assertion by propagating. */
    private static void assertHostileQueryNeverMatchesIndiscriminately(
            ProviderContractFixture fixture, long tenant, String text, Map<String, String> filters) {
        try {
            IndexedSearchResult result = fixture.provider().search(
                new IndexedSearchQuery(tenant, Set.of(ENTITY), text, filters, 50, null));
            Set<String> ids = result.hits().stream().map(h -> h.key().get("id")).collect(Collectors.toSet());
            assertThat(ids.containsAll(Set.of("abuse-1", "abuse-2")))
                .as("hostile query must never match every seeded probe row indiscriminately")
                .isFalse();
        } catch (InvalidIndexedSearchQueryException expected) {
            // a legitimate SPI-level refusal, not the unwrapped provider crash this dimension forbids.
        }
    }

    public static Stream<DynamicNode> hubLane(HubSearchContractFixture fixture) {
        return Stream.of(
            DynamicContainer.dynamicContainer(HubLaneDimension.ISOLATION_END_TO_END.name(),
                isolationEndToEndTests(fixture)),
            DynamicContainer.dynamicContainer(HubLaneDimension.ALLOWLIST_ENFORCEMENT.name(),
                allowlistEnforcementTests(fixture)),
            DynamicContainer.dynamicContainer(HubLaneDimension.CHANGE_FEED_TRIGGERS.name(),
                changeFeedTriggerTests(fixture)),
            DynamicContainer.dynamicContainer(HubLaneDimension.SCOPE_CHANGE_EFFECTIVE_NEXT_QUERY.name(),
                scopeChangeTests(fixture)),
            DynamicContainer.dynamicContainer(HubLaneDimension.REBUILD_WHILE_READS_CONTINUE.name(),
                rebuildWhileReadsContinueTests(fixture)),
            DynamicContainer.dynamicContainer(HubLaneDimension.STALE_CANONICAL_ROWS.name(),
                staleCanonicalRowsTests(fixture))
        );
    }

    private static List<DynamicTest> isolationEndToEndTests(HubSearchContractFixture fixture) {
        return List.of(
            DynamicTest.dynamicTest("a query never returns another tenant's row", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenantA = driver.provisionTenant();
                TenantHandle tenantB = driver.provisionTenant();
                UserHandle userA = driver.provisionUser(tenantA, Set.of());
                driver.provisionUser(tenantB, Set.of());
                driver.applySearchIndex(tenantA, HUB_INDEX, entity, entity.columns());
                driver.applySearchIndex(tenantB, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenantA, entity, List.of(probeRow("a-1")));
                driver.ingestCanonicalRows(tenantB, entity, List.of(probeRow("b-1")));
                driver.awaitProjectionConvergence(tenantA, HUB_INDEX, TIMEOUT);
                driver.awaitProjectionConvergence(tenantB, HUB_INDEX, TIMEOUT);

                CanonicalSearchResult resultA = driver.query(tenantA, userA, PROBE_TEXT, Map.of());
                assertThat(resultA.hits()).extracting(h -> h.key().get("rowKey")).containsExactly("a-1");
            }),
            DynamicTest.dynamicTest("a user without the entity's scope grant never sees its rows", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenant = driver.provisionTenant();
                UserHandle boundElsewhere = driver.provisionUser(tenant, Set.of("scope:some-other-entity"));
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(probeRow("s-1")));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);

                CanonicalSearchResult result = driver.query(tenant, boundElsewhere, PROBE_TEXT, Map.of());
                assertThat(result.hits()).isEmpty();
            })
        );
    }

    private static List<DynamicTest> allowlistEnforcementTests(HubSearchContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "a column absent from the SearchIndex manifest never appears in a hit's fields", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = new CanonicalEntityDef(ENTITY_WITH_SECRET, List.of(
                    new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false),
                    new CanonicalFieldDef("secret", null, false)));
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, entity,
                    List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
                driver.ingestCanonicalRows(tenant, entity, List.of(
                    new CanonicalRow("al-1", Map.of("probe", PROBE_TEXT, "secret", "do-not-leak"))));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);

                CanonicalSearchResult result = driver.query(tenant, user, PROBE_TEXT, Map.of());
                assertThat(result.hits()).isNotEmpty();
                assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.fields()).doesNotContainKey("secret"));
            }));
    }

    private static List<DynamicTest> scopeChangeTests(HubSearchContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "revoking a scope grant is effective on the very next query, no convergence wait needed", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenant = driver.provisionTenant();
                // A second, unrelated scope keeps the user scope-bound (non-empty grant set) after the
                // revoke below — revoking the user's ONLY scope would instead flip them back to
                // "zero scope:* roles = unscoped, tenant-wide" and they'd keep seeing the row, which
                // would prove nothing about the revoke being effective.
                UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + ENTITY, "scope:some-other-entity"));
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(probeRow("sc-1")));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isNotEmpty();

                driver.revokeScope(tenant, user, "scope:" + ENTITY);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isEmpty();
            }));
    }

    /** #701's "resolution merge" trigger (design spec §8: "reflected in the index within one worker
     *  cycle") — PR #734 review round 4: kept genuinely provider-neutral by construction. The harness
     *  never builds its own two-row fixture or touches how a driver models crosswalk/resolution
     *  internally (a rowKey-rewrite hack would be exactly the "synthetic" contract the review
     *  rejected) — it only orchestrates {@link HubSearchContractDriver#prepareResolvableMergeScenario}'s
     *  own five steps: provision, observe two, trigger, converge, observe one. Every provider's own
     *  driver carries the real complexity of proving those two results were genuinely independent
     *  identities that a real merge converged, not just two rows sharing a database. Every genuinely
     *  incremental trigger below uses {@link HubSearchContractDriver#awaitOneProjectionCycle} — never
     *  {@link HubSearchContractDriver#awaitProjectionConvergence}'s own retry-until-timeout loop, which
     *  could never actually falsify the "within one worker cycle" claim (PR #734 review round 5) — a
     *  trigger genuinely routed through a rebuild is the one documented exception, and says so at its
     *  own call site rather than silently reusing the same claim.
     *
     *  <p>Resolution split is reintroduced below (icreated/lumnik#737) as its own self-contained
     *  {@code DynamicTest} — it builds its own scenario rather than sharing the merge test's, since
     *  JUnit does not guarantee dynamic tests in one {@code List.of(...)} run in declaration order.
     *  Connector deletion is still NOT covered, and {@code deleteConnector} still does not exist on
     *  {@link HubSearchContractDriver} at all (PR #734 review round 6 — a dead-but-declared method in a
     *  public contract is a false guarantee) — full rationale in the design spec's
     *  {@code CHANGE_FEED_TRIGGERS} section; tracked as icreated/lumnik#736, which reintroduces it with
     *  real, executable semantics once its underlying gap closes. */
    private static List<DynamicTest> changeFeedTriggerTests(HubSearchContractFixture fixture) {
        return List.of(
            DynamicTest.dynamicTest("a resolution merge converges to one row, not two", () -> {
                HubSearchContractDriver driver = fixture.driver();
                TenantHandle tenant = driver.provisionTenant();
                ResolvableMergeScenario scenario = driver.prepareResolvableMergeScenario(tenant, HUB_INDEX, PROBE_TEXT);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, scenario.user(), PROBE_TEXT, Map.of()).hits()).hasSize(2);

                driver.mergeResolvableScenario(tenant, scenario);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);

                assertThat(driver.query(tenant, scenario.user(), PROBE_TEXT, Map.of()).hits()).hasSize(1);
            }),
            DynamicTest.dynamicTest("a resolution split reverts a merge back to two rows, not one", () -> {
                // Self-contained (icreated/lumnik#737): builds its own scenario rather than reusing the
                // merge test's local variable — see this method's own javadoc.
                HubSearchContractDriver driver = fixture.driver();
                TenantHandle tenant = driver.provisionTenant();
                ResolvableMergeScenario scenario = driver.prepareResolvableMergeScenario(tenant, HUB_INDEX, PROBE_TEXT);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, scenario.user(), PROBE_TEXT, Map.of()).hits()).hasSize(2);

                driver.mergeResolvableScenario(tenant, scenario);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, scenario.user(), PROBE_TEXT, Map.of()).hits()).hasSize(1);

                driver.splitResolvableScenario(tenant, scenario);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, scenario.user(), PROBE_TEXT, Map.of()).hits()).hasSize(2);
            }),
            DynamicTest.dynamicTest("dropping a field from the SearchIndex alone purges it, the entity keeps the column", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_searchindex_purge", List.of(
                    new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false),
                    new CanonicalFieldDef("secret", null, false)));
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(
                    new CanonicalRow("sip-1", Map.of("probe", PROBE_TEXT, "secret", "x"))));
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits().get(0).fields())
                    .containsKey("secret");

                // The Entity itself is untouched (still declares both columns) — only the SearchIndex's
                // own field allowlist narrows, unlike the sibling "Entity re-apply" test below.
                driver.applySearchIndex(tenant, HUB_INDEX, entity,
                    List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits().get(0).fields())
                    .doesNotContainKey("secret");
            }),
            DynamicTest.dynamicTest("dropping a column via Entity re-apply removes it from every future hit", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef withSecret = new CanonicalEntityDef(ENTITY_WITH_SECRET, List.of(
                    new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false),
                    new CanonicalFieldDef("secret", null, false)));
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, withSecret, withSecret.columns());
                driver.ingestCanonicalRows(tenant, withSecret, List.of(
                    new CanonicalRow("re-1", Map.of("probe", PROBE_TEXT, "secret", "x"))));
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits().get(0).fields())
                    .containsKey("secret");

                CanonicalEntityDef withoutSecret = new CanonicalEntityDef(ENTITY_WITH_SECRET,
                    List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
                driver.reapplyEntity(tenant, withoutSecret);
                driver.awaitOneProjectionCycle(tenant, HUB_INDEX);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits().get(0).fields())
                    .doesNotContainKey("secret");
            }),
            DynamicTest.dynamicTest("deleting a tenant removes every one of its rows", () -> {
                // A documented rebuild-based EXCEPTION to "within one worker cycle" (PR #734 review
                // round 5) — deleteTenant is a hard-delete-shaped trigger (icreated/lumnik#732's own
                // gap: a bare SQL DELETE leaves nothing behind for the incremental row-1 scan to mark),
                // so this deliberately keeps awaitProjectionConvergence's rebuild fallback rather than
                // claiming a one-cycle convergence that would not hold.
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(probeRow("dt-1")));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isNotEmpty();

                driver.deleteTenant(tenant);
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isEmpty();
            })
        );
    }

    private static List<DynamicTest> rebuildWhileReadsContinueTests(HubSearchContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "reads keep succeeding during a rebuild; the new generation is visible only once it completes", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(probeRow("rb-1")));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isNotEmpty();

                // Ingested BEFORE triggerRebuild — never converged incrementally — so the ONLY way
                // this row can become visible is the rebuild's own scan. Its own generation coming
                // back READY with only "rb-1" (the incremental path's doing, not the rebuild's) would
                // prove nothing about the rebuild having genuinely reproduced a more-complete
                // generation, which is the claim this test's name actually makes (#674 finding).
                String rebuildOnlyText = "rebuildscanonlyvisibletext";
                driver.ingestCanonicalRows(tenant, entity, List.of(
                    new CanonicalRow("rb-2", Map.of("probe", rebuildOnlyText))));

                RebuildOutcome triggered = driver.triggerRebuild(tenant, HUB_INDEX);
                assertThat(triggered).isInstanceOf(RebuildOutcome.Started.class);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits())
                    .as("reads must keep succeeding while a rebuild is in flight")
                    .isNotEmpty();

                RebuildTerminalOutcome outcome = driver.awaitRebuildCompletion(tenant, HUB_INDEX, triggered, TIMEOUT);
                assertThat(outcome).isEqualTo(RebuildTerminalOutcome.READY);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits())
                    .as("the new generation must still contain the row present before the rebuild")
                    .isNotEmpty();
                assertThat(driver.query(tenant, user, rebuildOnlyText, Map.of()).hits())
                    .as("the new generation must contain the row only the rebuild's own scan could have "
                        + "picked up, proving it is genuinely more complete, not just some generation "
                        + "that happened to still contain the first row")
                    .isNotEmpty();
            }));
    }

    private static List<DynamicTest> staleCanonicalRowsTests(HubSearchContractFixture fixture) {
        return List.of(DynamicTest.dynamicTest(
            "a deleted canonical row's projection disappears once convergence completes "
                + "(via the #732 rebuild fallback for PG today)", () -> {
                HubSearchContractDriver driver = fixture.driver();
                CanonicalEntityDef entity = probeEntity();
                TenantHandle tenant = driver.provisionTenant();
                UserHandle user = driver.provisionUser(tenant, Set.of());
                driver.applySearchIndex(tenant, HUB_INDEX, entity, entity.columns());
                driver.ingestCanonicalRows(tenant, entity, List.of(probeRow("st-1")));
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);
                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isNotEmpty();

                driver.deleteCanonicalRow(tenant, entity, "st-1");
                driver.awaitProjectionConvergence(tenant, HUB_INDEX, TIMEOUT);

                assertThat(driver.query(tenant, user, PROBE_TEXT, Map.of()).hits()).isEmpty();
            }));
    }

    private static String seedPublishedGeneration(
            ProviderContractFixture fixture, long tenant, List<ProjectionDocument> docs) {
        String gen = fixture.sink().beginGeneration(tenant, INDEX, definition());
        fixture.sink().upsert(tenant, INDEX, gen, docs);
        fixture.sink().publish(tenant, INDEX, gen);
        return gen;
    }

    private static CanonicalEntityDef probeEntity() {
        return new CanonicalEntityDef(ENTITY, List.of(
            new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
    }

    private static CanonicalRow probeRow(String rowKey) {
        return new CanonicalRow(rowKey, Map.of("probe", PROBE_TEXT));
    }

    private static ProjectionDefinition definition() {
        return new ProjectionDefinition(ENTITY, "simple",
            List.of(new ProjectionField("probe", ProjectionFieldMode.TEXT, false)));
    }

    private static ProjectionDocument probeDoc(String id) {
        return new ProjectionDocument(id, Map.of("id", id), Map.of("probe", PROBE_TEXT));
    }

    private static ProjectionDocument probeDocWithText(String id, String text) {
        return new ProjectionDocument(id, Map.of("id", id), Map.of("probe", text));
    }

    private static IndexedSearchQuery probeQuery(long tenantId, Set<String> allowedEntities) {
        return new IndexedSearchQuery(tenantId, allowedEntities, PROBE_TEXT, Map.of(), 50, null);
    }
}

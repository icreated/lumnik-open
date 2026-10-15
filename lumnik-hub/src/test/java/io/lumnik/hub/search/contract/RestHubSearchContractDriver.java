package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.search.contract.CanonicalEntityDef;
import io.lumnik.testkit.search.contract.CanonicalFieldDef;
import io.lumnik.testkit.search.contract.CanonicalRow;
import io.lumnik.testkit.search.contract.CanonicalSearchResult;
import io.lumnik.testkit.search.contract.HubSearchContractDriver;
import io.lumnik.testkit.search.contract.RebuildOutcome;
import io.lumnik.testkit.search.contract.RebuildTerminalOutcome;
import io.lumnik.testkit.search.contract.ResolvableMergeScenario;
import io.lumnik.testkit.search.contract.TenantHandle;
import io.lumnik.testkit.search.contract.UserHandle;
import io.lumnik.testkit.search.contract.CanonicalSearchHit;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import jakarta.enterprise.event.Event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The one real {@link HubSearchContractDriver} implementation (#674) — the only class allowed to
 *  translate testkit-owned DTOs into real ingestion/manifest-apply/worker/#703-rebuild calls and
 *  RestAssured HTTP calls, since it is the only module that can reach any of that machinery. Manually
 *  constructed by each hub-lane IT from its own {@code @Inject} fields — not itself a CDI bean, since
 *  it is test-tree-only code. */
public final class RestHubSearchContractDriver implements HubSearchContractDriver {

    private final AgroalDataSource adminDs;
    private final Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    private final SearchProjectionWorker worker;

    // PR #734 review finding (P2): static, not per-instance — every IT in this package constructs
    // its own fresh driver, and an instance field reset to the same starting value on every
    // construction meant every test method's first provisioned tenant collided on the same numeric
    // id (800001) once run together in the same JVM. Static makes every provisioned tenant id
    // genuinely unique for the life of the JVM, matching what "a fresh driver per test" is meant to
    // buy in the first place, without needing every caller to scope its querying user by hand.
    private static final AtomicLong tenantSeq = new AtomicLong(800_000);
    private final AtomicLong userSeq = new AtomicLong();
    private final Map<String, Set<String>> scopesByUserId = new ConcurrentHashMap<>();
    private final Set<String> tablesEnsured = ConcurrentHashMap.newKeySet();
    private final Map<TenantEntityKey, AppliedIndex> indexesByEntityName = new ConcurrentHashMap<>();

    /** The SearchIndex this driver most recently applied over one entity — {@code reapplyEntity}
     *  (Task 13) uses this to keep a shrinking Entity's own SearchIndex declaration in sync (dropping
     *  a field the entity no longer has), exactly what a real integrator would need to do by hand:
     *  {@code SearchIndexDefinitionCheck} invalidates any declaration naming a column the entity
     *  dropped (CLAUDE.md: "never refusing the Entity"), and an invalid declaration's {@code /rebuild}
     *  door itself refuses (409) — so there is no way to observe a dropped field disappear from
     *  results without also narrowing the SearchIndex that named it. */
    private record AppliedIndex(String indexName, List<CanonicalFieldDef> fields) {}

    /** {@code indexesByEntityName} keys on the (tenant, entity) pair, not the entity name alone —
     *  two tenants applying the same entity name are otherwise indistinguishable, and only worked by
     *  accident so long as no dimension exercised that overlap concurrently. */
    private record TenantEntityKey(long tenantId, String entityName) {}

    public RestHubSearchContractDriver(AgroalDataSource adminDs, Event<ConnectorRunCompleted> connectorRunCompletedEvent,
                                        SearchProjectionWorker worker) {
        this.adminDs = adminDs;
        this.connectorRunCompletedEvent = connectorRunCompletedEvent;
        this.worker = worker;
    }

    @Override
    public TenantHandle provisionTenant() {
        return new TenantHandle(tenantSeq.incrementAndGet());
    }

    @Override
    public UserHandle provisionUser(TenantHandle tenant, Set<String> scopeTags) {
        String userId = "contract-suite-user-" + userSeq.incrementAndGet();
        scopesByUserId.put(userId, new CopyOnWriteArraySet<>(scopeTags));
        return new UserHandle(userId, tenant.tenantId());
    }

    @Override
    public void grantScope(TenantHandle tenant, UserHandle user, String scopeTag) {
        scopesByUserId.computeIfAbsent(user.userId(), k -> new CopyOnWriteArraySet<>()).add(scopeTag);
    }

    @Override
    public void revokeScope(TenantHandle tenant, UserHandle user, String scopeTag) {
        scopesByUserId.getOrDefault(user.userId(), Set.of()).remove(scopeTag);
    }

    /** {@code lm_user} is always included — every {@code /api/search} caller needs it regardless of
     *  scope grants (`@RequiresPermission("lm_user")` on `SearchResource`); the scope tags this user
     *  was provisioned/granted with ride alongside it in the same `groups` claim. */
    private String bearerToken(UserHandle user) {
        Set<String> roles = new HashSet<>(scopesByUserId.getOrDefault(user.userId(), Set.of()));
        roles.add("lm_user");
        return NoBypassOidcProfile.token(roles, null, user.tenantId());
    }

    /** {@code EntityApplyResource} is gated on {@code lm_admin} while {@code SearchIndexApplyResource}
     *  is gated on {@code lm_integrator} (confirmed by reading both classes' {@code @RequiresPermission}
     *  directly — the two doors do NOT share a role) — carrying both roles in the one token this driver
     *  uses for every manifest-apply call is simpler than minting a second, differently-scoped token
     *  purely to satisfy an exact-match {@code PermissionInterceptor} that only needs ONE of them to
     *  match per call. */
    String integratorToken(TenantHandle tenant) {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "lm_admin"), null, tenant.tenantId());
    }

    static Header bearer(String jwt) {
        return new Header("Authorization", "Bearer " + jwt);
    }

    /** #674 finding: a driver-only bookkeeping column, never one of {@code CanonicalEntityDef}'s own
     *  declared columns — see {@link #ensureEntityApplied}'s own comment for why the entity's real
     *  canonical columns cannot serve as this driver's row identity (the probe fixture's canonical
     *  column value is the same literal constant for every row). Declared as this entity's {@code
     *  match:} column (see {@link #applyEntityManifest}) instead of the first canonical column, so
     *  every physical row is genuinely distinguishable to the real search index, and its key_fields
     *  come back as exactly {@code {"rowKey": <value>}} — matching {@code HubSearchContractDriver}'s
     *  own documented {@code key().get("rowKey")} convention without any translation in {@link
     *  #query}. */
    private static final String ROW_KEY_COLUMN = "rowKey";

    /** {@link #prepareResolvableMergeScenario}'s own entity name — distinct from every other entity
     *  this driver builds, so its dedicated two-source, resolution:-based manifest never collides
     *  with the match:-based shape {@link #ensureEntityApplied} builds for everyone else. */
    private static final String RESOLVABLE_MERGE_ENTITY = "contract_suite_resolvable_merge";

    // Package-visible, not private (/simplify finding): aResolutionMergeConvergesToOneRow addresses
    // crosswalkEntry's `source` param with this directly, alongside secondSourceTableName — one
    // accessor per physical source instead of a second, identically-behaving mainSourceTableName
    // wrapper that only ever returned this.
    static String tableName(CanonicalEntityDef entity) {
        return "t_contract_suite_" + entity.name();
    }

    private static String viewName(CanonicalEntityDef entity) {
        return "v_contract_suite_" + entity.name();
    }

    /** Physical source table + a {@code match:} Entity manifest applying it — idempotent per entity
     *  name (a second call for the same entity is a no-op on the table, and a harmless re-apply of the
     *  same manifest). Every canonical column is its own source column, mapped 1:1 — no {@code src_}
     *  prefix convention is required by {@link EntitySourceTableFixture}, and keeping the names
     *  identical keeps this driver's YAML generation trivial. */
    private void ensureEntityApplied(TenantHandle tenant, CanonicalEntityDef entity) {
        ensureMainTableCreated(entity);
        applyEntityManifest(tenant, entity);
    }

    /** The table-creation half alone — extracted (PR #734 review round 4) so {@link
     *  #prepareResolvableMergeScenario} can create this same system-column-prelude shape WITHOUT
     *  {@link #applyEntityManifest}'s match:-based manifest, since its own entity needs a
     *  resolution:-based one instead. Delegates to {@link #createBackingTableIfNeeded}, the shared
     *  recipe every physical source table in this driver uses (/simplify finding: this and {@link
     *  #ensureAltTableCreated} used to duplicate the same body). */
    private void ensureMainTableCreated(CanonicalEntityDef entity) {
        createBackingTableIfNeeded(tableName(entity), viewName(entity), entity.columns());
    }

    /** ROW_KEY_COLUMN alongside {@code canonicalColumns} (#674 finding: every probe-fixture row
     *  shares the identical literal canonical column value, so that column can never double as this
     *  driver's own row identity the way deleteCanonicalRow/rewriteMatchKey originally
     *  assumed — a dedicated, driver-only bookkeeping column is what actually makes each physical
     *  row distinguishable), plus the system-column prelude every physical source table in this
     *  driver needs: {@code _ingested_at} drives {@code SearchProjectionRunHook}'s row-1 scan
     *  ({@code EntitySourceTableFixture} itself does not add it, since its other callers — export/
     *  registry ITs — never need change-feed marks); {@code id BIGSERIAL PRIMARY KEY} because {@code
     *  EntityResolutionPass.paginateByPk} hardcodes a keyset cursor over {@code src.id} for ANY
     *  resolution-bearing entity's physical table (mirrors {@code EntityResolutionRunHookTest}'s own
     *  fixture); {@code _run_id UUID} because {@code EntityResolutionPass.resolveByRunId}'s
     *  pagination query filters {@code src._run_id = ?} unconditionally for ANY resolution-bearing
     *  entity (PR #734 review finding — mirrors {@code DynamicTableWriter}'s real system-column
     *  prelude, which this driver's own minimal fixture never carried until this was added). Added
     *  unconditionally (the table is still empty at creation time) rather than only for
     *  resolution-using callers, since it is a no-op for every other one. */
    private void createBackingTableIfNeeded(String table, String view, List<CanonicalFieldDef> canonicalColumns) {
        if (tablesEnsured.add(table)) {
            String[] columns = Stream.concat(
                    canonicalColumns.stream().map(CanonicalFieldDef::name), Stream.of(ROW_KEY_COLUMN))
                .toArray(String[]::new);
            try {
                EntitySourceTableFixture.create(adminDs, table, view, columns);
                try (Connection c = adminDs.getConnection(); var st = c.createStatement()) {
                    st.execute("ALTER TABLE connector." + table
                        + " ADD COLUMN _ingested_at timestamptz NOT NULL DEFAULT now()");
                    st.execute("ALTER TABLE connector." + table + " ADD COLUMN id BIGSERIAL PRIMARY KEY");
                    st.execute("ALTER TABLE connector." + table + " ADD COLUMN _run_id UUID");
                }
            } catch (Exception e) {
                throw new IllegalStateException("failed to create backing table " + table, e);
            }
        }
    }

    /** The manifest-apply half alone — reused by {@link #ensureEntityApplied} (first apply, table just
     *  created) and by {@code reapplyEntity} (Task 13: same entity, a changed column list, table
     *  already exists) so the two never drift into two different YAML-building implementations. */
    private void applyEntityManifest(TenantHandle tenant, CanonicalEntityDef entity) {
        String table = tableName(entity);
        StringBuilder map = new StringBuilder();
        for (CanonicalFieldDef col : entity.columns()) {
            map.append("      ").append(col.name()).append(": ").append(col.name()).append('\n');
        }
        // ROW_KEY_COLUMN unconditionally, regardless of entity.columns() — see its own javadoc.
        map.append("      ").append(ROW_KEY_COLUMN).append(": ").append(ROW_KEY_COLUMN).append('\n');
        String manifest = "kind: Entity\n"
            + "name: " + entity.name() + "\n"
            + "match: [" + ROW_KEY_COLUMN + "]\n"
            // EntityManifestParser's `scopes:` list holds plain scope TAGS, never the Keycloak
            // `scope:` role prefix (ScopeGrants.nameProblem validates against
            // ^[a-z][a-z0-9_-]{0,40}$) — confirmed by reading EntityManifestParser and every real
            // passing Entity manifest literal in this test tree; a "scope:" prefix here would be
            // refused as an invalid, non-lowercase tag, not stripped.
            + "scopes: [" + entity.name() + "]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + map;

        RestAssured.given()
            .config(YamlBody.CONFIG)
            .header(bearer(integratorToken(tenant)))
            .contentType("application/yaml")
            .body(manifest)
            .post("/api/entities/apply")
            .then().log().ifValidationFails()
            .statusCode(200);
    }

    @Override
    public void applySearchIndex(TenantHandle tenant, String indexName, CanonicalEntityDef entity,
                                  List<CanonicalFieldDef> fields) {
        ensureEntityApplied(tenant, entity);
        applySearchIndexManifest(tenant, indexName, entity, fields);
    }

    /** The SearchIndex-apply half alone — extracted (PR #734 review round 4) so {@link
     *  #prepareResolvableMergeScenario} can apply a SearchIndex over an entity it manifested itself
     *  (resolution:-based, not match:-based) without {@link #applySearchIndex}'s own {@link
     *  #ensureEntityApplied} call re-applying (and clobbering) that manifest with a match:-based one. */
    private void applySearchIndexManifest(TenantHandle tenant, String indexName, CanonicalEntityDef entity,
                                           List<CanonicalFieldDef> fields) {
        StringBuilder spec = new StringBuilder();
        for (CanonicalFieldDef field : fields) {
            spec.append("    - name: ").append(field.name()).append('\n');
            if (field.mode() != null) {
                spec.append("      match: ").append(field.mode().name().toLowerCase(Locale.ROOT)).append('\n');
            }
            if (field.facet()) {
                spec.append("      facet: true\n");
            }
        }
        String manifest = "apiVersion: search.lumnik.io/v1\n"
            + "kind: SearchIndex\n"
            + "metadata:\n"
            + "  name: " + indexName + "\n"
            + "spec:\n"
            + "  entity: " + entity.name() + "\n"
            + "  fields:\n"
            + spec;

        RestAssured.given()
            .config(YamlBody.CONFIG)
            .header(bearer(integratorToken(tenant)))
            .contentType("application/yaml")
            .body(manifest)
            .post("/api/search-indexes/apply")
            .then().log().ifValidationFails()
            .statusCode(200);
        indexesByEntityName.put(new TenantEntityKey(tenant.tenantId(), entity.name()), new AppliedIndex(indexName, fields));
    }

    @Override
    public void ingestCanonicalRows(TenantHandle tenant, CanonicalEntityDef entity, List<CanonicalRow> rows) {
        ensureEntityApplied(tenant, entity);
        List<String> columns = entity.columns().stream().map(CanonicalFieldDef::name).toList();
        // _run_id stamped with THIS run's own id (not a throwaway) — EntityResolutionPass.resolveByRunId
        // only processes rows whose _run_id matches the id carried by the very ConnectorRunCompleted
        // event fired below, so a fresh insert must be discoverable by that same run.
        UUID runId = UUID.randomUUID();
        String sql = "INSERT INTO connector." + tableName(entity) + " (tenant_id, "
            + String.join(", ", columns.stream().map(c -> "\"" + c + "\"").toList()) + ", \"" + ROW_KEY_COLUMN
            + "\", _run_id) VALUES (?, "
            + String.join(", ", columns.stream().map(c -> "?").toList()) + ", ?, ?)";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (CanonicalRow row : rows) {
                ps.setLong(1, tenant.tenantId());
                int idx = 2;
                for (String col : columns) ps.setString(idx++, row.values().get(col));
                ps.setString(idx++, row.rowKey());
                ps.setObject(idx, runId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to insert canonical rows for " + entity.name(), e);
        }
        fireConnectorRunCompleted(tenant, runId);
    }

    @Override
    public void deleteCanonicalRow(TenantHandle tenant, CanonicalEntityDef entity, String rowKey) {
        // rowKey is this driver's own synthetic identifier, held in ROW_KEY_COLUMN — see its javadoc
        // for why a real canonical column (e.g. the probe fixture's) cannot serve this role.
        String sql = "DELETE FROM connector." + tableName(entity) + " WHERE tenant_id = ? AND \"" + ROW_KEY_COLUMN + "\" = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant.tenantId());
            ps.setString(2, rowKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete canonical row " + rowKey, e);
        }
        // Task 13 scratch verification (ScratchDeleteDetectionCheck, not committed): a bare SQL DELETE
        // leaves nothing behind for SearchProjectionRunHook's `_ingested_at > watermark` scan to mark —
        // confirmed empirically (row still returned by /api/search after a full discoveryTick() drain).
        // No tombstone/_deleted_at convention exists anywhere in connector.t_* dynamic-table writers to
        // set instead (grepped DynamicTableWriter and the whole connector package). See
        // awaitProjectionConvergence's own comment for the fallback this flag drives.
        markHardDeleteAndFireEvent(tenant);
    }

    /** Overload for callers with nothing to stamp with a run id (a hard delete leaves no row behind
     *  for resolution to process) — a throwaway id is harmless: {@code resolveByRunId} simply finds
     *  zero matching rows. */
    private void markHardDeleteAndFireEvent(TenantHandle tenant) {
        markHardDeleteAndFireEvent(tenant, UUID.randomUUID());
    }

    private void markHardDeleteAndFireEvent(TenantHandle tenant, UUID runId) {
        pendingHardDeleteFallback = true;
        fireConnectorRunCompleted(tenant, runId);
    }

    private void fireConnectorRunCompleted(TenantHandle tenant, UUID runId) {
        try {
            connectorRunCompletedEvent
                .fireAsync(new ConnectorRunCompleted(UUID.randomUUID(), tenant.tenantId(), runId, Instant.now()))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("ConnectorRunCompleted delivery failed for tenant " + tenant.tenantId(), e);
        }
    }

    /** The documented contract for out-of-band hard deletes (icreated/lumnik#732): the projection follows
     *  ingestion, so a bare DELETE is only ever observed by a rebuild. Set by {@link #deleteCanonicalRow} / {@link #deleteTenant}, and by {@link
     *  #rewriteMatchKey} (#674 finding: changing a row's own match-column value orphans its
     *  OLD canonical key exactly like a hard delete does — see {@link #awaitProjectionConvergence}).
     *  Plain instance field, not per-tenant/per-index state: every test
     *  method that needs it constructs its own fresh driver and drives it sequentially (see every IT
     *  in this package), so there is never more than one hard delete/rename in flight against one
     *  driver instance at a time. */
    private volatile boolean pendingHardDeleteFallback;

    @Override
    public void awaitProjectionConvergence(TenantHandle tenant, String indexName, Duration timeout) {
        // Documented contract (icreated/lumnik#732), not a workaround: SearchProjectionRunHook's row-1 scan
        // and SearchProjectionReconciler's sweep mark rows by `_ingested_at > watermark` — an ingestion
        // signal — so neither observes a hard SQL DELETE, which leaves nothing behind to mark. No
        // tombstone/_deleted_at convention exists in the connector.t_* writers, and no product path
        // issues a row-level DELETE on a DynamicTableWriter connector.t_* table (POST …/purge only reaches
        // tables that carry an endpoint_id column, i.e. hybrid targets). A hard delete is therefore only ever removed from results by a rebuild, so
        // whenever a hard delete is the reason this call was made, run one to completion; the incremental
        // drain below structurally cannot observe it. RestHubSearchContractDriverChangeFeedIT pins this.
        if (pendingHardDeleteFallback) {
            pendingHardDeleteFallback = false;
            RebuildOutcome triggered = triggerRebuild(tenant, indexName);
            if (!(triggered instanceof RebuildOutcome.Started)) {
                throw new IllegalStateException(
                    "hard-delete fallback could not trigger a rebuild for index " + indexName + ": " + triggered);
            }
            awaitRebuildCompletion(tenant, indexName, triggered, timeout);
            return;
        }
        // discoveryTick() drains whatever marks fireConnectorRunCompleted's synchronous wait already
        // guaranteed are produced — one call is enough for a single small test fixture; looped up to
        // timeout defensively in case a future dimension ingests enough rows to need two batches.
        long deadline = System.nanoTime() + timeout.toNanos();
        worker.discoveryTick();
        while (System.nanoTime() < deadline) {
            sleepBriefly();
            worker.discoveryTick();
        }
    }

    @Override
    public void awaitOneProjectionCycle(TenantHandle tenant, String indexName) {
        // fireConnectorRunCompleted (and every trigger that reuses it) already synchronously awaits
        // its own CompletableFuture before returning, so whatever mark a trigger produces — a
        // SearchProjectionRunHook row-1 scan mark, or CascadeResolver.resolveWithin's own KEY mark —
        // already exists in core.search_projection_mark by the time a caller reaches this call; a
        // single discoveryTick() is not a race against async work still in flight, it is draining
        // work that already happened.
        worker.discoveryTick();
    }

    @Override
    public void reapplyEntity(TenantHandle tenant, CanonicalEntityDef updated) {
        applyEntityManifest(tenant, updated);
        // Keep this entity's own SearchIndex (if any) from going `invalid` (SearchIndexDefinitionCheck)
        // over a column `updated` no longer has — see AppliedIndex's javadoc for why this driver must
        // do this itself rather than relying on a rebuild/reproject to notice on its own.
        AppliedIndex applied = indexesByEntityName.get(new TenantEntityKey(tenant.tenantId(), updated.name()));
        if (applied != null) {
            Set<String> stillCanonical = updated.columns().stream().map(CanonicalFieldDef::name).collect(Collectors.toSet());
            List<CanonicalFieldDef> narrowed = applied.fields().stream().filter(f -> stillCanonical.contains(f.name())).toList();
            if (!narrowed.equals(applied.fields())) {
                applySearchIndex(tenant, applied.indexName(), updated, narrowed);
            }
        }
    }

    /** Builds a {@code resolution:}-declared sibling manifest over TWO physical sources (mirrors
     *  {@code EntityResolutionRunHookTest}'s own fixture shape for the identity/cascade config:
     *  {@code identity} keyed by {@link #ROW_KEY_COLUMN}, one {@code cascade} rule matching on that
     *  same column, per source) so {@code EntityResolutionRunHook} — not {@code SearchProjectionRunHook}
     *  — is the one deciding each row's own crosswalk entry. Applying a second, differently-named
     *  Entity manifest over the main physical table (plus a second, independent table — see {@link
     *  #ensureSecondSourceTableApplied}) is exactly what {@code EntityManifest.sources} supports.
     *
     *  <p>PR #734 review, round 3: TWO sources, not one — {@code core.entity_identity}'s own {@code
     *  uq_entity_identity_cardinality} constraint ("at most one identity per source") makes two rows
     *  of the SAME source converging onto one {@code entity_id} structurally impossible (confirmed
     *  empirically: a raw, unhandled {@code PSQLException}, filed as a follow-up issue — production's
     *  own bug, out of #674's scope to fix). A genuine merge needs two rows from DIFFERENT sources —
     *  the crosswalk is a multi-source golden-record mechanism, never an intra-source dedup one.
     *  Package-visible (not private): {@code aResolutionMergeConvergesToOneRow} needs the exact
     *  manifest name to call {@link #crosswalkEntry}/{@link #assertSameIdentity}. */
    String resolutionEntityName(CanonicalEntityDef entity) {
        return entity.name() + "_resolution";
    }

    /** The second, independent source's table name — see {@link #ensureSecondSourceTableApplied}'s
     *  own javadoc for why a genuine cross-identity merge needs a second SOURCE, not just a second
     *  row of the main one. */
    String secondSourceTableName(CanonicalEntityDef entity) {
        return tableName(entity) + "_alt";
    }

    // Package-visible, not private (PR #734 review finding): aResolutionMergeConvergesToOneRow calls
    // this explicitly BEFORE ingestCanonicalRows, so both rows get their own resolved crosswalk entry
    // at ingest time — a merge tested afterward genuinely converges two pre-existing identities,
    // instead of the first-ever resolution pass processing only the row a later "merge" step touched.
    void ensureResolutionEntityApplied(TenantHandle tenant, CanonicalEntityDef entity) {
        ensureSecondSourceTableApplied(tenant, entity);
        String manifest = "kind: Entity\n"
            + "name: " + resolutionEntityName(entity) + "\n"
            + "sources:\n"
            + "  - table: " + tableName(entity) + "\n"
            + "    map:\n"
            + "      " + ROW_KEY_COLUMN + ": " + ROW_KEY_COLUMN + "\n"
            + "  - table: " + secondSourceTableName(entity) + "\n"
            + "    map:\n"
            + "      " + ROW_KEY_COLUMN + ": " + ROW_KEY_COLUMN + "\n"
            + resolutionBlock(entity);
        RestAssured.given()
            .config(YamlBody.CONFIG)
            .header(bearer(integratorToken(tenant)))
            .contentType("application/yaml")
            .body(manifest)
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    /** The {@code resolution:} tail shared by {@link #ensureResolutionEntityApplied}'s sibling
     *  manifest and {@link #prepareResolvableMergeScenario}'s own (/simplify finding: the two used
     *  to duplicate this byte-for-byte) — {@code identity:} keyed by {@link #ROW_KEY_COLUMN} per
     *  source, one {@code exact} cascade rule matching on that same column. */
    private String resolutionBlock(CanonicalEntityDef entity) {
        return "resolution:\n"
            + "  identity:\n"
            + "    " + tableName(entity) + ": " + ROW_KEY_COLUMN + "\n"
            + "    " + secondSourceTableName(entity) + ": " + ROW_KEY_COLUMN + "\n"
            + "  cascade:\n"
            + "    - id: " + ROW_KEY_COLUMN + "\n"
            + "      exact: { fields: [" + ROW_KEY_COLUMN + "] }\n";
    }

    /** #701's "resolution merge" change-feed trigger, as the harness's own genuine, provider-neutral
     *  scenario (PR #734 review round 4) — unlike {@link #ensureResolutionEntityApplied}'s sibling
     *  manifest (built for {@code aResolutionMergeConvergesToOneRow}'s OWN detailed diagnostic, kept
     *  separate), THIS entity is itself {@code resolution:}-declared, not {@code match:} — the only
     *  way a genuine crosswalk merge is ALSO what the search index observes: {@code
     *  EntityViewCompiler.outerSelectResolved} groups by the resolved {@code entity_id}, not a
     *  physical match column, and {@code match:}/{@code resolution:} are mutually exclusive on one
     *  manifest (EntityManifest.java's own javadoc). {@code rm-1} lives in the main table, {@code
     *  rm-2} in the second source — {@link #mergeResolvableScenario} relies on this exact
     *  convention. */
    @Override
    public ResolvableMergeScenario prepareResolvableMergeScenario(TenantHandle tenant, String indexName, String probeText) {
        CanonicalEntityDef entity = new CanonicalEntityDef(RESOLVABLE_MERGE_ENTITY,
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));

        ensureMainTableCreated(entity);
        ensureAltTableCreated(secondSourceTableName(entity), entity.columns());
        String manifest = "kind: Entity\n"
            + "name: " + entity.name() + "\n"
            + "scopes: [" + entity.name() + "]\n"
            + "sources:\n"
            + "  - table: " + tableName(entity) + "\n"
            + "    map:\n"
            + "      " + ROW_KEY_COLUMN + ": " + ROW_KEY_COLUMN + "\n"
            + "      probe: probe\n"
            + "  - table: " + secondSourceTableName(entity) + "\n"
            + "    map:\n"
            + "      " + ROW_KEY_COLUMN + ": " + ROW_KEY_COLUMN + "\n"
            + "      probe: probe\n"
            + resolutionBlock(entity);
        RestAssured.given()
            .config(YamlBody.CONFIG)
            .header(bearer(integratorToken(tenant)))
            .contentType("application/yaml")
            .body(manifest)
            .post("/api/entities/apply")
            .then().statusCode(200);

        applySearchIndexManifest(tenant, indexName, entity, entity.columns());

        ingestResolvableMergeRow(tenant, tableName(entity), "rm-1", probeText);
        ingestResolvableMergeRow(tenant, secondSourceTableName(entity), "rm-2", probeText);

        UserHandle user = provisionUser(tenant, Set.of("scope:" + entity.name()));
        return new ResolvableMergeScenario(entity, user, "rm-1", "rm-2");
    }

    private void ingestResolvableMergeRow(TenantHandle tenant, String table, String rowKey, String probeText) {
        UUID runId = UUID.randomUUID();
        String sql = "INSERT INTO connector." + table
            + " (tenant_id, \"probe\", \"" + ROW_KEY_COLUMN + "\", _run_id) VALUES (?, ?, ?, ?)";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant.tenantId());
            ps.setString(2, probeText);
            ps.setString(3, rowKey);
            ps.setObject(4, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to insert resolvable-merge row into " + table, e);
        }
        fireConnectorRunCompleted(tenant, runId);
    }

    /** A second, wholly independent physical source — see {@link #resolutionEntityName}'s own javadoc
     *  for why a genuine cross-identity merge structurally requires this rather than a second row of
     *  the main table. Same minimal system-column prelude (id/_run_id/_ingested_at) as {@link
     *  #ensureEntityApplied}, via the same {@link EntitySourceTableFixture} RLS+policy+grant recipe
     *  every physical source table in this driver uses; nothing ever compiles or queries this table's
     *  own view, so its name is never referenced beyond the drop-if-exists {@code create} call. */
    private void ensureSecondSourceTableApplied(TenantHandle tenant, CanonicalEntityDef entity) {
        ensureAltTableCreated(secondSourceTableName(entity), List.of());
    }

    /** Generalized (PR #734 review round 4) from {@link #ensureSecondSourceTableApplied}'s original
     *  rowKey-only shape so {@link #prepareResolvableMergeScenario} can give its own second source a
     *  real, search-indexed canonical column too. Delegates to {@link #createBackingTableIfNeeded} —
     *  see {@link #ensureMainTableCreated}'s identical note. */
    private void ensureAltTableCreated(String table, List<CanonicalFieldDef> canonicalColumns) {
        createBackingTableIfNeeded(table, "v_" + table, canonicalColumns);
    }

    /** Ingests one row into the second, independent source (package-visible: driver-specific plumbing
     *  for a genuine cross-source resolution merge, not part of the neutral {@code
     *  HubSearchContractDriver} contract — see {@code aResolutionMergeConvergesToOneRow}). */
    void ingestIntoSecondSource(TenantHandle tenant, CanonicalEntityDef entity, String rowKey) {
        ensureSecondSourceTableApplied(tenant, entity);
        UUID runId = UUID.randomUUID();
        String sql = "INSERT INTO connector." + secondSourceTableName(entity)
            + " (tenant_id, \"" + ROW_KEY_COLUMN + "\", _run_id) VALUES (?, ?, ?)";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant.tenantId());
            ps.setString(2, rowKey);
            ps.setObject(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to insert into second source for " + entity.name(), e);
        }
        fireConnectorRunCompleted(tenant, runId);
    }

    /** Re-fires resolution over one row of the second source WITHOUT touching any cascade-relevant
     *  field — the mechanism {@code assertSameIdentity}'s own javadoc explains: recording a "same"
     *  assertion never retroactively rewrites an already-resolved crosswalk row on its own,
     *  {@code CascadeResolver} only consults it at pass-time. */
    void reprocessSecondSourceRow(TenantHandle tenant, CanonicalEntityDef entity, String rowKey) {
        reprocessRow(tenant, secondSourceTableName(entity), rowKey);
    }

    /** Generalized (PR #734 review round 4) from {@link #reprocessSecondSourceRow}'s original
     *  entity-scoped form so {@link #prepareResolvableMergeScenario}'s own merge can reprocess
     *  either of its two physical tables by name. */
    private void reprocessRow(TenantHandle tenant, String table, String rowKey) {
        UUID runId = UUID.randomUUID();
        String sql = "UPDATE connector." + table
            + " SET _ingested_at = now(), _run_id = ? WHERE tenant_id = ? AND \"" + ROW_KEY_COLUMN + "\" = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            ps.setLong(2, tenant.tenantId());
            ps.setString(3, rowKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to reprocess row in " + table, e);
        }
        fireConnectorRunCompleted(tenant, runId);
    }

    /** {@code core.entity_identity} is the only place that proves {@code EntityResolutionPass}
     *  genuinely produced a crosswalk entry for a given source row — the search index alone cannot
     *  (PR #734 review finding): its own {@code match:} column is a SEPARATE, driver-only bookkeeping
     *  mechanism (see {@link #rewriteMatchKey}), so "the index collapsed to one row" proves
     *  nothing about whether resolution's own async hook actually succeeded. {@code identityRef} is
     *  the crosswalk's own immutable surrogate ({@code core.entity_identity.identity_ref}) — the ONLY
     *  key {@code POST /api/entities/{name}/assertions/same} accepts (#619); {@code resolutionManifestName}
     *  is the exact {@code kind: Entity} manifest name the crosswalk was resolved under — either
     *  {@link #resolutionEntityName} (the sibling-manifest shape {@code aResolutionMergeConvergesToOneRow}
     *  uses) or an entity's own {@code name()} (the shape {@link #prepareResolvableMergeScenario}
     *  uses, where the SAME entity is both search-indexed and resolution-declared); {@code source} is
     *  a physical table name; {@code sourceId} is the physical row's {@link #ROW_KEY_COLUMN} value.
     *  Returns {@code null} when no crosswalk row exists yet. */
    record CrosswalkEntry(String state, UUID entityId, UUID identityRef) {}

    CrosswalkEntry crosswalkEntry(TenantHandle tenant, String resolutionManifestName, String source, String sourceId) {
        String sql = "SELECT state, entity_id, identity_ref FROM core.entity_identity "
            + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant.tenantId());
            ps.setString(2, resolutionManifestName.toLowerCase(Locale.ROOT));
            ps.setString(3, source);
            ps.setString(4, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                    ? new CrosswalkEntry(rs.getString(1), (UUID) rs.getObject(2), (UUID) rs.getObject(3))
                    : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to query entity_identity for " + resolutionManifestName, e);
        }
    }

    /** {@code POST /api/entities/{name}/assertions/same} (#619) — the ONE production-sanctioned way
     *  to converge two ALREADY-resolved crosswalk entries onto the same {@code entity_id}: {@link
     *  io.lumnik.hub.entity.resolution.CascadeResolver}'s own "never silently recycle a resolved
     *  link" guard (PR #734 review finding, confirmed empirically — refuses as {@code ambiguous}, not
     *  {@code resolved}) refuses exactly the alternative this method replaces, a cascade-field
     *  mutation on an already-resolved row. A human "same" assertion is the ONE documented exception
     *  to that guard. Package-visible: {@code aResolutionMergeConvergesToOneRow} calls this directly
     *  against two DIFFERENT sources' crosswalk entries — the only shape it can safely target (see
     *  {@link #resolutionEntityName}'s own javadoc on {@code uq_entity_identity_cardinality}). */
    void assertSameIdentity(TenantHandle tenant, String resolutionManifestName, UUID leftRef, UUID rightRef) {
        RestAssured.given()
            .header(bearer(integratorToken(tenant)))
            .contentType("application/json")
            .body(Map.of("leftRef", leftRef.toString(), "rightRef", rightRef.toString()))
            .post("/api/entities/" + resolutionManifestName + "/assertions/same")
            .then().statusCode(204);
    }

    /** The retraction door (icreated/lumnik#737) — {@code POST .../assertions/distinct} on a pair
     *  previously asserted {@code same} already flips the stored verdict in place ({@code
     *  EntityIdentityRepository.upsertAssertion}'s own {@code ON CONFLICT ... DO UPDATE}), no
     *  separate "delete" door needed. Same shape as {@link #assertSameIdentity}. */
    void assertDistinctIdentity(TenantHandle tenant, String resolutionManifestName, UUID leftRef, UUID rightRef) {
        RestAssured.given()
            .header(bearer(integratorToken(tenant)))
            .contentType("application/json")
            .body(Map.of("leftRef", leftRef.toString(), "rightRef", rightRef.toString()))
            .post("/api/entities/" + resolutionManifestName + "/assertions/distinct")
            .then().statusCode(204);
    }

    /** The genuine merge for a {@link #prepareResolvableMergeScenario}-built scenario — its own
     *  interface method (/simplify finding: previously inferred inside a driver-private bookkeeping
     *  set, an unenforced convention nothing tied back to the scenario that populated it; a real,
     *  typed distinction is what the two genuinely different mechanisms below need, not runtime
     *  dispatch). See {@link #assertSameIdentity}'s own
     *  javadoc for why a human "same" assertion, not a cascade-field mutation, is the only correct
     *  mechanism. {@code intoRowKey} lives in the main table, {@code fromRowKey} in the second source
     *  (the convention {@code prepareResolvableMergeScenario} itself establishes). No separate
     *  rebuild fallback needed here, unlike {@link #rewriteMatchKey}'s own rowKey-rewrite path:
     *  {@code CascadeResolver.resolveWithin}'s own KEY-mark production (confirmed against {@code
     *  SearchProjectionResolutionIT.mergeViaAssertionRealizedAtNextResolveMarksBothTheSurvivorAndThePriorEntity})
     *  marks BOTH the surviving and the displaced {@code entity_id}, which the ordinary incremental
     *  {@code discoveryTick()} drain already consumes — reflected in the index within one worker
     *  cycle, exactly the design spec §8 wording this dimension is named for. */
    @Override
    public void mergeResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario) {
        CanonicalEntityDef entity = scenario.entity();
        String manifestName = entity.name();
        CrosswalkEntry into = crosswalkEntry(tenant, manifestName, tableName(entity), scenario.intoRowKey());
        CrosswalkEntry from = crosswalkEntry(tenant, manifestName, secondSourceTableName(entity), scenario.fromRowKey());
        if (into == null || from == null) {
            throw new IllegalStateException(
                "prepareResolvableMergeScenario's own rows must already be resolved before a merge");
        }
        assertSameIdentity(tenant, manifestName, from.identityRef(), into.identityRef());
        reprocessRow(tenant, secondSourceTableName(entity), scenario.fromRowKey());
    }

    /** The genuine crosswalk-driven split for an already-merged {@link
     *  #prepareResolvableMergeScenario}-built scenario (icreated/lumnik#737) — retracts the exact
     *  same assertion {@link #mergeResolvableScenario} recorded and reprocesses the same "from" row
     *  it moved, mirroring its structure exactly. */
    @Override
    public void splitResolvableScenario(TenantHandle tenant, ResolvableMergeScenario scenario) {
        CanonicalEntityDef entity = scenario.entity();
        String manifestName = entity.name();
        CrosswalkEntry into = crosswalkEntry(tenant, manifestName, tableName(entity), scenario.intoRowKey());
        CrosswalkEntry from = crosswalkEntry(tenant, manifestName, secondSourceTableName(entity), scenario.fromRowKey());
        if (into == null || from == null) {
            throw new IllegalStateException(
                "prepareResolvableMergeScenario's own rows must already be resolved before a split");
        }
        assertDistinctIdentity(tenant, manifestName, from.identityRef(), into.identityRef());
        reprocessRow(tenant, secondSourceTableName(entity), scenario.fromRowKey());
    }

    /** The change-feed-observability half of the #701 "resolution merge" trigger — a bare rewrite of
     *  {@link #ROW_KEY_COLUMN} (the driver's own {@code match:} bookkeeping column), never a real
     *  crosswalk merge (see {@link #mergeResolvableScenario} for that). Package-visible, not part of
     *  {@link HubSearchContractDriver} (PR #734 review round 7: keeping this on the public contract
     *  under the name {@code triggerResolutionMerge} implied it performed a genuine merge — the same
     *  false guarantee round 6 removed for split/deleteConnector). Exercised only by {@code
     *  RestHubSearchContractDriverChangeFeedIT.aResolutionMergeConvergesToOneRow}, which drives the
     *  actual crosswalk merge separately via {@link #assertSameIdentity} and uses this call only to
     *  prove the index-observable rowKey rewrite falls back to a rebuild like a hard delete does. */
    void rewriteMatchKey(TenantHandle tenant, CanonicalEntityDef entity, String intoRowKey, String fromRowKey) {
        ensureResolutionEntityApplied(tenant, entity);
        // The change-feed-observable half of this #701 trigger (design spec §8: "each #701 trigger is
        // reflected in the index within one worker cycle"): ROW_KEY_COLUMN drives the MAIN entity's own
        // `match:`-based view grouping (`match:` and `resolution:` are mutually exclusive on one
        // EntityManifest — EntityManifest.java's own javadoc). The GENUINE identity-resolution merge —
        // two pre-existing, cross-source crosswalk entries actually converging onto the same entity_id
        // — is a separate, independently proven concern (PR #734 review, round 3): see
        // aResolutionMergeConvergesToOneRow, which drives it directly via ensureResolutionEntityApplied
        // + ingestIntoSecondSource + assertSameIdentity, never through this rowKey rewrite. fromRowKey's
        // OLD canonical key is now orphaned in the search index exactly like a hard SQL DELETE would
        // leave it (SearchProjectionRunHook's row-1 scan only observes the row's NEW state, never a
        // diff against its old match value), so this falls back to a full rebuild, same as the
        // hard-delete path.
        String sql = "UPDATE connector." + tableName(entity)
            + " SET \"" + ROW_KEY_COLUMN + "\" = ?, _ingested_at = now() WHERE tenant_id = ? AND \"" + ROW_KEY_COLUMN + "\" = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, intoRowKey);
            ps.setLong(2, tenant.tenantId());
            ps.setString(3, fromRowKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("resolution merge failed for " + entity.name(), e);
        }
        markHardDeleteAndFireEvent(tenant);
    }

    // deleteConnector removed (PR #734 review round 6) — see HubSearchContractDriver's own note:
    // it had no executable semantics any dimension actually exercised, and keeping a dead-but-
    // declared implementation here was a false guarantee. icreated/lumnik#736 reintroduces it with
    // real semantics. triggerResolutionSplit itself is reintroduced above as splitResolvableScenario
    // (icreated/lumnik#737).

    @Override
    public void deleteTenant(TenantHandle tenant) {
        // Approximates "tenant deletion" by removing every row this driver has ingested for the
        // tenant, across every entity it has ever applied — NOT the real platform-level tenant-delete
        // admin operation (its REST surface was not located during planning; grep
        // lumnik-hub/src/main/java/io/lumnik/hub/api/platform/ if a future dimension needs the literal
        // admin door instead of this approximation).
        for (String table : tablesEnsured) {
            try (Connection c = adminDs.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM connector." + table + " WHERE tenant_id = ?")) {
                ps.setLong(1, tenant.tenantId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("failed to delete tenant rows from " + table, e);
            }
        }
        // Same hard-DELETE gap as deleteCanonicalRow — see awaitProjectionConvergence.
        markHardDeleteAndFireEvent(tenant);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CanonicalSearchResult query(TenantHandle tenant, UserHandle asUser, String text, Map<String, List<String>> facets) {
        RequestSpecification req = RestAssured.given().header(bearer(bearerToken(asUser))).queryParam("q", text);
        for (Map.Entry<String, List<String>> facet : facets.entrySet()) {
            for (String value : facet.getValue()) req = req.queryParam("facet." + facet.getKey(), value);
        }
        Response response = req.get("/api/search");

        int status = response.statusCode();
        if (status == 501 || status == 503) {
            return new CanonicalSearchResult(List.of(), Map.of(), true, response.jsonPath().getString("reason"));
        }
        response.then().statusCode(200);

        List<CanonicalSearchHit> hits = new ArrayList<>();
        for (Map<String, Object> hitJson : (List<Map<String, Object>>) (List<?>) response.jsonPath().getList("hits")) {
            hits.add(new CanonicalSearchHit(
                (String) hitJson.get("entity"),
                (Map<String, String>) hitJson.get("key"),
                (Map<String, String>) hitJson.get("fields"),
                (Map<String, List<String>>) hitJson.getOrDefault("highlights", Map.of()),
                (String) hitJson.get("canonicalLink")));
        }
        return new CanonicalSearchResult(hits, Map.of(), false, null);
    }

    /** #730: the {@code asOf} the hub's {@code /api/search} reports for this query — {@code null} when the
     *  answer says freshness is unknown. Not part of the provider-neutral driver contract. */
    public Instant queryAsOf(UserHandle asUser, String text) {
        Response response = RestAssured.given().header(bearer(bearerToken(asUser))).queryParam("q", text)
            .get("/api/search");
        response.then().statusCode(200);
        String asOf = response.jsonPath().getString("asOf");
        return asOf == null ? null : Instant.parse(asOf);
    }

    @Override
    public RebuildOutcome triggerRebuild(TenantHandle tenant, String indexName) {
        Response response = RestAssured.given()
            .header(bearer(integratorToken(tenant)))
            .post("/api/platform/search/index/" + indexName + "/rebuild");

        if (response.statusCode() == 202) {
            return new RebuildOutcome.Started(response.jsonPath().getString("runId"));
        }
        String error = response.jsonPath().getString("error");
        // "too many rebuilds in flight" is RebuildClaim's fifth variant (SearchOperationsResource.java,
        // near line 179) — confirm its exact wording against that file before trusting this branch in
        // an assertion; every 409/503 this switch doesn't otherwise recognize falls into it.
        return switch (error == null ? "" : error) {
            case "rebuild already in progress" -> new RebuildOutcome.RebuildAlreadyRunning(response.jsonPath().getString("runId"));
            case "index busy" -> new RebuildOutcome.PairBusy();
            case "index declaration changed" -> new RebuildOutcome.DeclarationChanged();
            default -> new RebuildOutcome.TooManyInFlight();
        };
    }

    @Override
    public RebuildTerminalOutcome awaitRebuildCompletion(TenantHandle tenant, String indexName,
                                                           RebuildOutcome triggered, Duration timeout) {
        if (!(triggered instanceof RebuildOutcome.Started started)) {
            throw new IllegalArgumentException("awaitRebuildCompletion requires a Started outcome, got: " + triggered);
        }
        String runId = started.runId();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Response response = RestAssured.given()
                .header(bearer(integratorToken(tenant)))
                .get("/api/platform/search/index/" + indexName + "/status");
            response.then().statusCode(200);

            if (runId.equals(response.jsonPath().getString("lastRebuild.runId"))) {
                return switch (response.jsonPath().getString("lastRebuild.outcome")) {
                    case "ready" -> RebuildTerminalOutcome.READY;
                    case "failed" -> RebuildTerminalOutcome.FAILED;
                    case "cancelled" -> RebuildTerminalOutcome.CANCELLED;
                    default -> throw new IllegalStateException(
                        "unknown rebuild outcome: " + response.jsonPath().getString("lastRebuild.outcome"));
                };
            }
            // activeRun.runId == runId (still running) or neither field carries it yet (the narrow
            // window right after 202) — keep polling either way until timeout, exactly §7 of
            // 2026-09-27-search-operations-door-design.md's own lm search rebuild --wait algorithm.
            sleepBriefly();
        }
        return RebuildTerminalOutcome.STILL_RUNNING;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The post-run resolution pass (#613 lot #620, design doc §7): drives {@link CascadeResolver} over
 * the rows a connector run touched, over crosswalk rows stuck in {@code unresolved_provider}, or
 * over an entity's whole extent on demand — a separate pass, never inside {@code ChunkExecutor}
 * (the conservation invariant {@code records_in = records_out + records_skipped} is ingestion's
 * own accounting; this pass touches neither side of it).
 *
 * <p><b>Three work-selection queries, not one</b> (design doc §7): {@link #runIncremental} —
 * rows this run inserted, named by {@code _run_id}; {@link #runRetry} — crosswalk rows stuck in
 * {@code unresolved_provider}, driven by state, not by a run; {@link #runReplay} — every row of
 * one entity's sources, on demand or after a rule-version change. Each row is resolved through
 * {@link CascadeResolver#resolve}, which commits its own decision in its own transaction — so a
 * batch here is a bound on how many source rows are held in memory at once, not a transaction
 * boundary: an ambiguity at row 50,000 already cannot roll back row 1..49,999 (design doc §7).
 *
 * <p><b>The latest physical row, never a stale one.</b> A source identity is stable across content
 * changes, but the physical dynamic-table row is not: a changed value is a fresh {@code INSERT}
 * (a new {@code id}), with the previous row left in place (design doc §7, {@code
 * DynamicTableWriter.java:588}). {@link #runRetry} and {@link #runReplay} therefore select the
 * newest physical row per identity value ({@code DISTINCT ON … ORDER BY … id DESC}) — resolving
 * from a superseded row would fingerprint data the identity no longer carries. {@link
 * #runIncremental} needs the very same dedup: two content-changed rows for one identity can both
 * carry the SAME run's {@code _run_id} (a paginated source with an inconsistent snapshot, a
 * late-arriving correction mid-extraction), so {@link #resolveByRunId} reuses this mechanism too.
 *
 * <p><b>{@link #runIncremental} reads only the sources the triggering run wrote</b> (#904). No index
 * exists on a dynamic table's {@code _run_id} column, so reading a source is a sequential scan of the
 * whole table even when no row matches — and every endpoint run used to pay it for every source of
 * every resolution-bearing entity. {@link io.lumnik.hub.connector.event.ConnectorRunCompleted} now
 * names the tables the run wrote; every other source is skipped, and its entity still covered.
 * Ownership is deliberately NOT asked of {@code connector.dynamic_table}: a hub table is named after
 * the source table, so two connectors can write one {@code connector.t_<name>}, and the registry
 * keeps one row per mapping naming whichever registered it.
 */
@Startup
@ApplicationScoped
public class EntityResolutionPass {

  private static final String SCHEMA = "connector";
  private static final String UNRESOLVED_PROVIDER = "unresolved_provider";
  private static final int WINNERS_FETCH_SIZE = 10_000;
  private static final Logger LOG = Logger.getLogger(EntityResolutionPass.class);

  @Inject EntityRegistry registry;
  @Inject CascadeResolver resolver;
  @Inject ResolutionCoverageStore coverage;
  @Inject CrosswalkStatistics statistics;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @ConfigProperty(name = "lumnik.entity.resolution.batch-size", defaultValue = "500")
  int batchSize;

  /**
   * {@code @Startup} forces this to run at boot, not lazily on the first pass — same posture as
   * {@link FingerprintHmacKey}. {@code batchSize} is {@link #paginateByPk}'s page step: {@code 0}
   * would never advance past the first page (a pass that never ends), and a negative value fails
   * mid-pass inside the async {@link EntityResolutionRunHook}, where a misconfiguration would
   * otherwise be hard to diagnose.
   */
  @PostConstruct
  void validateConfig() {
    if (batchSize < 1) {
      throw new IllegalStateException("lumnik.entity.resolution.batch-size must be >= 1 (was "
          + batchSize + ") — it is the resolution pass's page step, and a non-positive one never pages through. "
          + "Set LUMNIK_ENTITY_RESOLUTION_BATCH_SIZE to a positive integer.");
    }
  }

  /**
   * Per-outcome tally of one pass call, across every source of every manifest it touched (#622
   * extends the original four-outcome tally with the observability counters {@link
   * ResolutionResult} carries). {@code long} fields: a full replay on a large tenant, or several
   * sources over a long ingestion history, can exceed {@link Integer#MAX_VALUE} rows over time —
   * an {@code int} would silently wrap to a negative count and misreport an operator-visible
   * outcome.
   *
   * <p>{@code ruleHits} is the per-rule distribution the design doc's observability ask names
   * directly: "a rule that never fires and a rule that fires on everything are both defects worth
   * seeing." It comes from nothing more than {@link ResolutionResult#ruleIdsInvolved()} — the rule
   * ids whose ledger event this call actually wrote — so a rule with zero entries here never
   * produced a decision across the whole pass.
   */
  public record PassSummary(long resolved, long ambiguous, long unresolvedNoRule, long unresolvedProvider,
      long newlyMinted, long providerCalls, long providerFailures, Map<String, Long> ruleHits) {

    static final PassSummary EMPTY = new PassSummary(0, 0, 0, 0, 0, 0, 0, Map.of());

    /**
     * Folds one source's total into the caller's running total across several sources/manifests.
     * Copies {@code ruleHits} only when BOTH sides could contribute something — when one side is
     * empty, the OTHER side's map is reused as-is (never mutated, just referenced unchanged), so
     * the common case of folding a still-empty accumulator, or a source that named no rule at
     * all, costs nothing (Copilot review on PR #645).
     */
    PassSummary plus(PassSummary other) {
      Map<String, Long> mergedHits = mergedHits(ruleHits, other.ruleHits);
      return new PassSummary(resolved + other.resolved, ambiguous + other.ambiguous,
          unresolvedNoRule + other.unresolvedNoRule, unresolvedProvider + other.unresolvedProvider,
          newlyMinted + other.newlyMinted, providerCalls + other.providerCalls,
          providerFailures + other.providerFailures, mergedHits);
    }

    /**
     * Tallies one row's full decision — the accumulator every pagination loop reduces over,
     * potentially millions of times in a full replay. Copies {@code ruleHits} only when {@code
     * result} actually names a rule (Copilot review on PR #645) — most exact-rule accepts/
     * ambiguities do, but {@code UNRESOLVED_NO_RULE} and an assertion-only decision never do, and
     * for those the existing map is reused unchanged rather than rebuilt for nothing.
     */
    PassSummary counting(ResolutionResult result) {
      long r = result.state() == EntityResolutionState.RESOLVED ? 1 : 0;
      long a = result.state() == EntityResolutionState.AMBIGUOUS ? 1 : 0;
      long n = result.state() == EntityResolutionState.UNRESOLVED_NO_RULE ? 1 : 0;
      long p = result.state() == EntityResolutionState.UNRESOLVED_PROVIDER ? 1 : 0;
      long minted = result.newlyMinted() ? 1 : 0;
      Map<String, Long> hits = ruleHits;
      if (!result.ruleIdsInvolved().isEmpty()) {
        hits = new HashMap<>(ruleHits);
        for (String ruleId : result.ruleIdsInvolved()) hits.merge(ruleId, 1L, Long::sum);
      }
      return new PassSummary(resolved + r, ambiguous + a, unresolvedNoRule + n, unresolvedProvider + p,
          newlyMinted + minted, providerCalls + result.providerCalls(),
          providerFailures + result.providerFailures(), hits);
    }

    /**
     * {@code left} merged with {@code right} — reusing whichever side is non-empty UNCHANGED
     * when the other is empty (never mutating either map: a record whose accessor could return a
     * different value after some unrelated call would itself be a correctness bug), and copying
     * only when both sides actually have something to fold together.
     */
    private static Map<String, Long> mergedHits(Map<String, Long> left, Map<String, Long> right) {
      if (right.isEmpty()) return left;
      if (left.isEmpty()) return right;
      Map<String, Long> merged = new HashMap<>(left);
      right.forEach((ruleId, count) -> merged.merge(ruleId, count, Long::sum));
      return merged;
    }

    public long rowsProcessed() {
      return resolved + ambiguous + unresolvedNoRule + unresolvedProvider;
    }
  }

  /**
   * Every source row this run stamped with its OWN {@code _run_id} — a fresh insert or a genuine
   * content change. A re-seen, unchanged row keeps the {@code _run_id} of whichever run first
   * inserted it (design doc §7, {@code DynamicTableWriter}'s {@code ON CONFLICT DO UPDATE SET
   * _ingested_at = now()}) and is therefore not part of this selection — the incremental
   * selection.
   *
   * <p>#748: each entity is processed on its own and, only after ALL its sources completed, records
   * the run as covered for that entity ({@link ResolutionCoverageStore}); a failing entity is logged,
   * left uncovered (its search {@code asOf} stays behind the run) and does not stop its siblings — the
   * first failure is rethrown after the loop.
   *
   * <p>Not knowing which tables the run wrote, this overload reads every source.
   */
  public PassSummary runIncremental(long tenantId, UUID runId) {
    return runIncremental(tenantId, runId, null);
  }

  /**
   * The same, reading only the sources whose {@code connector.<table>} is in {@code tablesWritten}
   * ({@link io.lumnik.hub.connector.event.ConnectorRunCompleted#tablesWritten}; {@code null} = unknown,
   * read every source). A source left out holds no row of this run, so its entity is still covered.
   */
  public PassSummary runIncremental(long tenantId, UUID runId, Set<String> tablesWritten) {
    PassSummary total = PassSummary.EMPTY;
    RuntimeException firstFailure = null;
    for (EntityManifest m : registry.all()) {
      if (m.resolution() == null) continue;
      try {
        total = total.plus(resolveEntityIncremental(tenantId, m, runId, tablesWritten));
        // After EVERY decision of this entity committed (each with its marks, same transaction): a visible
        // coverage row therefore implies its marks are queued or already applied (#748).
        coverage.cover(tenantId, m.name().toLowerCase(Locale.ROOT), List.of(runId));
      } catch (RuntimeException e) {
        LOG.warnf(e, "resolution pass tenant=%d run=%s entity=%s failed — not covered, asOf stays behind it",
            tenantId, runId, m.name());
        if (firstFailure == null) firstFailure = e;
      }
    }
    logged("incremental", tenantId, runId, total);
    if (firstFailure != null) throw firstFailure;
    return total;
  }

  PassSummary resolveEntityIncremental(long tenantId, EntityManifest m, UUID runId, Set<String> tablesWritten) {
    PassSummary total = PassSummary.EMPTY;
    for (EntityManifest.Source s : m.sources()) {
      if (tablesWritten != null && !tablesWritten.contains(SCHEMA + "." + s.table())) continue;
      total = total.plus(resolveByRunId(tenantId, m, s, runId));
    }
    return total;
  }

  /** Crosswalk rows of one entity stuck in {@code unresolved_provider} — the retry selection. */
  public PassSummary runRetry(long tenantId, String entityName) {
    EntityManifest m = requireResolvable(entityName);
    PassSummary total = PassSummary.EMPTY;
    for (EntityManifest.Source s : m.sources()) {
      total = total.plus(resolveRetryEligible(tenantId, m, s));
    }
    return logged("retry", tenantId, null, total);
  }

  /**
   * Every row of one entity's sources, latest content per identity — the replay selection. Covers, for
   * this entity only, exactly the runs that were already finished when the replay STARTED (#748).
   */
  public PassSummary runReplay(long tenantId, String entityName) {
    EntityManifest m = requireResolvable(entityName);
    String entityType = m.name().toLowerCase(Locale.ROOT);
    // Frozen BEFORE the scan: a run still active may commit after the scan crossed its table, so it is not
    // in the set and keeps blocking. Finished runs with an unknown (NULL) fence are included.
    List<UUID> finished = coverage.finishedUncoveredRuns(tenantId, entityType);
    PassSummary total = resolveEntityReplay(tenantId, m);
    coverage.cover(tenantId, entityType, finished);
    return logged("replay", tenantId, null, total);
  }

  PassSummary resolveEntityReplay(long tenantId, EntityManifest m) {
    PassSummary total = PassSummary.EMPTY;
    for (EntityManifest.Source s : m.sources()) total = total.plus(resolveAllLatest(tenantId, m, s));
    return total;
  }

  /**
   * The one place a pass's summary is logged (#622, icreated's review on PR #645) — centralized
   * here rather than in each caller (the async run hook, the manual replay door) so every trigger,
   * including one not yet wired to anything ({@link #runRetry} has no caller in this codebase
   * today), gets the same end-of-pass observability by construction. {@code rows_seen} is named
   * explicitly rather than left implicit in the sum of the other four counters — mirrors {@code
   * ConnectorEngine.finalizeRun}'s own end-of-run counters line.
   *
   * <p>{@code runId} is null for {@code retry}/{@code replay} (neither is driven by one) —
   * rendered as the literal {@code run=null} rather than omitted, so the field is always in the
   * same place in the line. For {@code incremental} it is never null, and belongs in the message:
   * two runs of the same tenant completing close together are otherwise indistinguishable in the
   * log (Sergey's review — this is exactly what {@code runId} threading into this method was
   * for). WARN, not INFO, when {@code providerFailures > 0} — the one figure here that should
   * surface without an operator grepping for it, same posture as {@code
   * ConnectorEngine.finalizeRun}'s own WARN-for-Failed/Partial.
   */
  PassSummary logged(String trigger, long tenantId, UUID runId, PassSummary total) {
    String message = "resolution pass tenant=%d trigger=%s run=%s: rows_seen=%d resolved=%d "
        + "ambiguous=%d unresolved_no_rule=%d unresolved_provider=%d newly_minted=%d "
        + "provider_calls=%d provider_failures=%d rule_hits=%s";
    Object[] args = {tenantId, trigger, runId, total.rowsProcessed(), total.resolved(),
        total.ambiguous(), total.unresolvedNoRule(), total.unresolvedProvider(), total.newlyMinted(),
        total.providerCalls(), total.providerFailures(), total.ruleHits()};
    if (total.providerFailures() > 0) {
      LOG.warnf(message, args);
    } else {
      LOG.infof(message, args);
    }
    return total;
  }

  private EntityManifest requireResolvable(String entityName) {
    EntityManifest m = registry.findReloadingIfMissing(entityName);
    if (m == null)
      throw new IllegalArgumentException("entity '" + entityName + "' not found");
    if (m.resolution() == null)
      throw new IllegalArgumentException("entity '" + entityName + "' has no resolution: policy");
    return m;
  }

  // -------------------------------------------------------------------------------------------
  // Per-source query dispatch. Shared shape: keyset-paginate the physical rows, translate each
  // through the source's map (physical column -> canonical column), resolve, tally.
  // -------------------------------------------------------------------------------------------

  /**
   * Everything one pagination run needs about "which source, resolved how" — bundled because
   * {@code tenantId}/{@code manifest}/{@code source}/{@code identitySrcCol}/{@code entityType}/
   * {@code runId} all travel together, unchanged, from a {@code resolve*} dispatch method through
   * {@link #paginateByPk} into {@link #resolveOne} for every row of one pagination run.
   */
  record ResolveTarget(long tenantId, EntityManifest manifest, EntityManifest.Source source,
      String identitySrcCol, String entityType, UUID runId, List<String> physicalCols) {

    static ResolveTarget of(long tenantId, EntityManifest m, EntityManifest.Source s, UUID runId) {
      String identitySrcCol = m.resolution().physicalIdentityColumn(s);
      List<String> physicalCols = new ArrayList<>(s.map().keySet());
      return new ResolveTarget(tenantId, m, s, identitySrcCol, m.name().toLowerCase(Locale.ROOT),
          runId, physicalCols);
    }
  }

  /**
   * Deduped to the latest row per identity among THIS run's own rows, same as retry/replay: two
   * content-changed physical rows for the same identity can both carry this run's {@code _run_id} (a paginated source with an inconsistent snapshot, a
   * late-arriving correction mid-extraction) — without the dedup, the older row is processed
   * first, fresh-mints or matches an entity, and the newer row's differently-computed resolution
   * is then refused by {@link CascadeResolver}'s "never silently recycle" guard: {@code ambiguous}
   * purely from intra-run processing order, not any genuine external conflict. Reusing {@link
   * #latestPerIdentitySql} also folds in its {@code IS NOT NULL} guard on the identity column
   * (core.entity_identity's {@code source_id} is {@code NOT NULL}, part of its PK, V49 — a row
   * with no declared identity value can never be given a crosswalk row).
   */
  private PassSummary resolveByRunId(long tenantId, EntityManifest m, EntityManifest.Source s, UUID runId) {
    ResolveTarget t = ResolveTarget.of(tenantId, m, s, runId);
    LatestPerIdentity q = latestPerIdentity(t, "", " AND src._run_id = ?");
    return paginateByPk(q, t, List.of(tenantId, runId));
  }

  private PassSummary resolveRetryEligible(long tenantId, EntityManifest m, EntityManifest.Source s) {
    ResolveTarget t = ResolveTarget.of(tenantId, m, s, null);
    String join = " JOIN core.entity_identity ei ON ei.tenant_id = src.tenant_id AND ei.entity_type = ?"
        + " AND ei.source = ? AND ei.source_id = src." + quoteIdent(t.identitySrcCol()) + "::text";
    LatestPerIdentity q = latestPerIdentity(t, join, " AND ei.state = '" + UNRESOLVED_PROVIDER + "'");
    return paginateByPk(q, t, List.of(t.entityType(), s.table(), tenantId));
  }

  private PassSummary resolveAllLatest(long tenantId, EntityManifest m, EntityManifest.Source s) {
    ResolveTarget t = ResolveTarget.of(tenantId, m, s, null);
    LatestPerIdentity q = latestPerIdentity(t, "", "");
    return paginateByPk(q, t, List.of(tenantId));
  }

  /**
   * The two statements of one "latest physical row per identity" selection (#904): {@code winners}
   * returns only the primary key of each identity's newest row past a lower bound ({@code src.id >
   * ?}), {@code page} reads the columns of a slice of those keys ({@code src.id = ANY(?)}), in key
   * order.
   */
  private record LatestPerIdentity(String winners, String page) {}

  /**
   * The shared selection {@link #resolveByRunId}, {@link #resolveRetryEligible} and {@link
   * #resolveAllLatest} all need, differing only in an extra JOIN (retry's crosswalk lookup) and
   * WHERE fragment (incremental's run, retry's state) — {@code joinClause}/{@code extraWhere} carry
   * exactly that difference, {@code ""} for replay's plain "every row" selection. Both statements
   * carry the same FROM/WHERE, so a page re-checks the filter its keys were chosen by: a crosswalk
   * row a concurrent pass already took out of {@code unresolved_provider} is not retried twice.
   */
  private static LatestPerIdentity latestPerIdentity(ResolveTarget t, String joinClause, String extraWhere) {
    String identity = "src." + quoteIdent(t.identitySrcCol());
    String from = " FROM " + SCHEMA + "." + quoteIdent(t.source().table()) + " src"
        + joinClause
        + " WHERE src.tenant_id = ?" + extraWhere;
    String winners = "SELECT DISTINCT ON (" + identity + ") src.id"
        + from + " AND src.id > ? AND " + identity + " IS NOT NULL"
        + " ORDER BY " + identity + ", src.id DESC";
    String page = "SELECT src.id" + selectList(t.physicalCols(), "src.")
        + from + " AND src.id = ANY(?) ORDER BY src.id";
    return new LatestPerIdentity(winners, page);
  }

  /**
   * The one pagination driver every {@code resolve*} method above reduces to (#904). The newest
   * row per identity is computed ONCE — {@link #winnersPast}, primary keys only, about 8 bytes per
   * identity (8 MB for a million) — then read and resolved {@code batchSize} keys at a time, in key
   * order: each identity once, by its highest {@code id}.
   *
   * <p>A row inserted while the pass runs (a key above the round's highest) is still resolved, after
   * the content it superseded: the next round asks for winners past the highest key just resolved,
   * until a round finds none — an index range on the primary key, not another full scan. Like any
   * keyset, a row whose key was drawn before a round's scan but committed after it is not seen; its
   * run, still active when the pass started, resolves it in its own incremental pass. The replay's
   * #748 contract is unchanged — it covers only the runs finished before it started, and their rows
   * are all visible to the first round.
   *
   * <p>Each statement runs on its own connection, released before any {@link #resolveOne} call —
   * resolving a row acquires its OWN admin connection ({@link EntityIdentityRepository}, reached
   * through {@link CascadeResolver}); holding a SELECT connection idle for the whole run would
   * waste a checkout every row's own resolution also needs, and deadlocks outright if the admin
   * pool is ever sized down to one connection.
   */
  private PassSummary paginateByPk(LatestPerIdentity q, ResolveTarget t, List<Object> fixedParams) {
    PassSummary total = PassSummary.EMPTY;
    long afterPk = 0;
    long[] winners;
    while ((winners = winnersPast(q.winners(), t, fixedParams, afterPk)).length > 0) {
      for (int from = 0; from < winners.length; from += batchSize) {
        long[] keys = Arrays.copyOfRange(winners, from, Math.min(from + batchSize, winners.length));
        for (Map<String, String> row : page(q.page(), t, fixedParams, keys)) {
          total = total.counting(resolveOne(t, row));
        }
        statistics.analyseIfNeverAnalysed();
      }
      afterPk = winners[winners.length - 1];
    }
    return total;
  }

  /**
   * Primary keys of the newest row per identity past {@code afterPk}, ascending. Streamed (a fetch
   * size inside a read-only transaction — pgjdbc otherwise buffers the whole result before the
   * first row), so the only thing held is the {@code long[]}; sorted here rather than by a second
   * {@code ORDER BY} the database would have to spill to disk for a large source.
   */
  long[] winnersPast(String sql, ResolveTarget t, List<Object> fixedParams, long afterPk) {
    try (Connection c = adminDs.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        int n = bind(ps, fixedParams);
        ps.setLong(n + 1, afterPk);
        ps.setFetchSize(WINNERS_FETCH_SIZE);
        LongStream.Builder keys = LongStream.builder();
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) keys.add(rs.getLong(1));
        }
        c.rollback();
        c.setAutoCommit(true);
        long[] sorted = keys.build().toArray();
        Arrays.sort(sorted);
        return sorted;
      } catch (SQLException e) {
        // Ending the transaction on a broken connection can fail too: never in place of the cause.
        try {
          c.rollback();
          c.setAutoCommit(true);
        } catch (SQLException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw queryFailed(t, e);
    }
  }

  private List<Map<String, String>> page(String sql, ResolveTarget t, List<Object> fixedParams, long[] keys) {
    try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
      int n = bind(ps, fixedParams);
      ps.setArray(n + 1, c.createArrayOf("bigint", Arrays.stream(keys).boxed().toArray()));
      return runQuery(ps, t.physicalCols());
    } catch (SQLException e) {
      throw queryFailed(t, e);
    }
  }

  private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    return params.size();
  }

  /**
   * Deliberately no e.getMessage() in this message: unlike EntityResolutionRunHook's async trigger
   * (always caught and logged), this path is also reachable synchronously from POST
   * /api/entities/{name}/resolve, and a raw driver message can name a physical table or column. The
   * cause (e) still carries full detail for server-side logs.
   */
  private static RuntimeException queryFailed(ResolveTarget t, SQLException e) {
    return new RuntimeException("EntityResolutionPass: pagination query failed for source '"
        + t.source().table() + "'", e);
  }

  private ResolutionResult resolveOne(ResolveTarget t, Map<String, String> row) {
    Map<String, String> raw = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : t.source().map().entrySet()) {
      raw.put(e.getValue(), row.get(e.getKey()));
    }
    String sourceId = row.get(t.identitySrcCol());
    return resolver.resolve(t.tenantId(), t.entityType(), t.source().table(), sourceId,
        t.manifest().resolution().cascade(), raw, t.runId());
  }

  // -------------------------------------------------------------------------------------------
  // Raw JDBC plumbing — admin datasource (BYPASSRLS), tenant bound explicitly in every query,
  // same posture as EntityIdentityRepository.
  // -------------------------------------------------------------------------------------------

  private static String selectList(List<String> physicalCols) {
    return selectList(physicalCols, "");
  }

  private static String selectList(List<String> physicalCols, String prefix) {
    StringBuilder sb = new StringBuilder();
    for (String col : physicalCols) {
      sb.append(", ").append(prefix).append(quoteIdent(col)).append("::text AS ").append(quoteIdent(col));
    }
    return sb.toString();
  }

  /**
   * Runs the already-bound {@code ps} and maps each row positionally: column 1 is {@code src.id}
   * (the page's order), then {@code physicalCols} in the exact order {@link #selectList} emitted
   * them — so a name-based lookup per column per row would only pay for a label search this fixed
   * layout never needs.
   */
  private List<Map<String, String>> runQuery(PreparedStatement ps, List<String> physicalCols) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      List<Map<String, String>> rows = new ArrayList<>();
      while (rs.next()) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < physicalCols.size(); i++) values.put(physicalCols.get(i), rs.getString(i + 2));
        rows.add(values);
      }
      return rows;
    }
  }
}

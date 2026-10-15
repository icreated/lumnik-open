package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lot #620 of the #613 entity-resolution arc (design doc §7): the post-run resolution pass —
 * work selection (incremental/retry/replay), the failure modes it must cover, and replay
 * idempotency. Runs against real Postgres on the admin (BYPASSRLS) datasource, the same posture as
 * {@link CascadeResolverTest} and {@link EntityIdentityRepository} themselves: this internal engine
 * enforces the tenant boundary in its own SQL rather than relying on RLS.
 *
 * <p>Source tables are hand-created here (not through {@code DynamicTableWriter}) but carry the
 * three system columns the pass actually reads — {@code id BIGSERIAL}, {@code _run_id UUID},
 * {@code tenant_id BIGINT} — matching {@code DynamicTableWriter.createTableDdl}'s shape closely
 * enough to exercise the real work-selection SQL.
 */
@QuarkusTest
class EntityResolutionPassIT {

  @Inject EntityResolutionPass pass;
  @Inject EntityRegistry registry;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "resit620";
  private static final String TABLE = "t_res_it620_erp";

  // A second entity/table carrying a `propose:` fallback — ONLY the retry test needs one, since
  // every OTHER test wants a fresh row to mint cleanly on first sight (sawAnyKey, no propose to
  // drag it to unresolved_provider first).
  private static final String ENTITY_TYPE_PROVIDER = "resit620b";
  private static final String TABLE_PROVIDER = "t_res_it620b_erp";

  private static final AtomicLong NEXT_TENANT = new AtomicLong(97_000);

  private static final String MANIFEST = """
      kind: Entity
      name: ResIt620
      sources:
        - table: t_res_it620_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_it620_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  private static final String MANIFEST_PROVIDER = """
      kind: Entity
      name: ResIt620b
      sources:
        - table: t_res_it620b_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_it620b_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
          - id: es
            propose: { provider: elasticsearch, acceptAbove: 0.95, reviewAbove: 0.80 }
      """;

  @Inject EntityFingerprintService fingerprintService;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String table : List.of(TABLE, TABLE_PROVIDER)) {
        st.execute("DROP TABLE IF EXISTS connector." + table);
        st.execute("CREATE TABLE connector." + table + " ("
            + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
            + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
      }
      st.execute("DELETE FROM core.entity_identity WHERE entity_type IN ('"
          + ENTITY_TYPE + "', '" + ENTITY_TYPE_PROVIDER + "')");
    }
    registry.put(new EntityManifestParser().parse(MANIFEST));
    registry.put(new EntityManifestParser().parse(MANIFEST_PROVIDER));
  }

  /** Seeds an already-resolved anchor identity + its matching email fingerprint under a
   *  fictitious, distinct source — bypassing the cascade entirely. Used both where a cold cascade
   *  behind a `propose:` fallback could never organically produce a resolved candidate (no
   *  CandidateProvider seam yet, #621), and to give a same-source row something DIFFERENT-source
   *  to legitimately match without tripping the v1 one-identity-per-source cardinality invariant. */
  private void seedResolvedAnchor(String entityType, long tenant, String source, String sourceId,
      UUID entityId, String email) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity "
             + "(tenant_id, entity_type, source, source_id, state, entity_id, decided_by, decided_at) "
             + "VALUES (?, ?, ?, ?, 'resolved', ?, 'automatic', now())")) {
      ps.setLong(1, tenant);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setObject(5, entityId);
      ps.executeUpdate();
    }
    RuleIdentity identity = fingerprintService.ruleIdentity("email", List.of("email"), List.of());
    byte[] fp = fingerprintService.fingerprint(tenant, entityType, "email", List.of("email"),
        List.of(), Map.of("email", email)).orElseThrow();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity_key "
             + "(tenant_id, entity_type, source, source_id, rule_declared_id, rule_version, key_fingerprint) "
             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setString(5, identity.ruleId());
      ps.setString(6, identity.ruleVersion());
      ps.setBytes(7, fp);
      ps.executeUpdate();
    }
  }

  private long freshTenant() {
    return NEXT_TENANT.getAndIncrement();
  }

  private long insertRow(long tenant, UUID runId, String customerId, String email) throws Exception {
    return insertRow(TABLE, tenant, runId, customerId, email);
  }

  private long insertRow(String table, long tenant, UUID runId, String customerId, String email)
      throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + table + " (tenant_id, _run_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, ?, ?, ?) RETURNING id")) {
      ps.setLong(1, tenant);
      if (runId == null) ps.setNull(2, Types.OTHER); else ps.setObject(2, runId);
      ps.setString(3, customerId);
      if (email == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, email);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private UUID insertConnector(long tenant) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector.connector (tenant_id, name, type_id) "
                 + "VALUES (?, ?, 'hello-world') RETURNING id")) {
      ps.setLong(1, tenant);
      ps.setString(2, "resit620-" + UUID.randomUUID());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  private UUID insertConnectorRun(long tenant, long recordsIn, long recordsOut, long recordsSkipped)
      throws Exception {
    UUID connectorId = insertConnector(tenant);
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector.connector_run "
                 + "(tenant_id, connector_id, status, sync_mode, records_in, records_out, records_skipped, "
                 + "triggered_by) VALUES (?, ?, 'Completed', 'Incremental', ?, ?, ?, 'manual') "
                 + "RETURNING id")) {
      ps.setLong(1, tenant);
      ps.setObject(2, connectorId);
      ps.setLong(3, recordsIn);
      ps.setLong(4, recordsOut);
      ps.setLong(5, recordsSkipped);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  private Map<String, Object> readConnectorRunCounters(UUID runId) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT records_in, records_out, records_skipped FROM connector.connector_run WHERE id = ?")) {
      ps.setObject(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Map<String, Object> out = new HashMap<>();
        out.put("in", rs.getLong(1));
        out.put("out", rs.getLong(2));
        out.put("skipped", rs.getLong(3));
        return out;
      }
    }
  }

  private record CrosswalkRow(String state, UUID entityId, String decidedBy) {}

  private CrosswalkRow readIdentity(long tenant, String sourceId) throws Exception {
    return readIdentity(ENTITY_TYPE, TABLE, tenant, sourceId);
  }

  private CrosswalkRow readIdentity(String entityType, String table, long tenant, String sourceId)
      throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT state, entity_id, decided_by FROM core.entity_identity "
                 + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?")) {
      ps.setLong(1, tenant);
      ps.setString(2, entityType);
      ps.setString(3, table);
      ps.setString(4, sourceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("crosswalk row for source_id '%s'", sourceId).isTrue();
        return new CrosswalkRow(rs.getString(1), (UUID) rs.getObject(2), rs.getString(3));
      }
    }
  }

  @Test
  void incremental_pass_leaves_the_run_conservation_counters_untouched() throws Exception {
    long tenant = freshTenant();
    UUID runId = insertConnectorRun(tenant, 3, 3, 0);
    insertRow(tenant, runId, "cons-1", "cons1@example.com");

    pass.runIncremental(tenant, runId);

    Map<String, Object> counters = readConnectorRunCounters(runId);
    assertThat(counters).containsEntry("in", 3L).containsEntry("out", 3L).containsEntry("skipped", 0L);
  }

  /** #889: two rows of one source on one exact key used to abort the pass at the second row — the
   *  cardinality constraint refused its upsert — and every row after it stayed unresolved, on every
   *  run and every replay. */
  @Test
  void a_same_source_duplicate_neither_aborts_the_pass_nor_strands_the_rows_after_it() throws Exception {
    long tenant = freshTenant();
    UUID runId = insertConnectorRun(tenant, 3, 3, 0);
    insertRow(tenant, runId, "dup-1", "same@example.com");
    insertRow(tenant, runId, "dup-2", "same@example.com");
    insertRow(tenant, runId, "after-1", "after@example.com");

    assertThatCode(() -> pass.runIncremental(tenant, runId)).doesNotThrowAnyException();

    assertThat(readIdentity(tenant, "dup-1").state()).isEqualTo("resolved");
    assertThat(readIdentity(tenant, "dup-2").state()).isEqualTo("resolved");
    assertThat(readIdentity(tenant, "dup-2").entityId())
        .isNotEqualTo(readIdentity(tenant, "dup-1").entityId());
    assertThat(readIdentity(tenant, "after-1").state()).isEqualTo("resolved");
  }

  @Test
  void incremental_pass_resolves_only_the_rows_this_run_touched() throws Exception {
    long tenant = freshTenant();
    UUID thisRun = insertConnectorRun(tenant, 1, 1, 0);
    UUID otherRun = insertConnectorRun(tenant, 1, 1, 0);
    insertRow(tenant, thisRun, "inc-1", "inc1@example.com");
    insertRow(tenant, otherRun, "inc-2", "inc2@example.com"); // a different run — must be ignored

    pass.runIncremental(tenant, thisRun);

    assertThat(readIdentity(tenant, "inc-1").state()).isEqualTo("resolved");
    // inc-2 was never touched by thisRun, so no crosswalk row should exist for it at all.
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT 1 FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ? "
                 + "AND source = ? AND source_id = ?")) {
      ps.setLong(1, tenant);
      ps.setString(2, ENTITY_TYPE);
      ps.setString(3, TABLE);
      ps.setString(4, "inc-2");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("a row from a DIFFERENT run must not be resolved by this call").isFalse();
      }
    }
  }

  /**
   * {@code resolveByRunId} lacked the {@code IS NOT NULL} guard on the identity column that
   * {@code latestPerIdentitySql} (retry/replay) already carries. A row whose declared identity
   * column is NULL would otherwise reach {@code resolver.resolve()} with a null {@code sourceId},
   * and {@code core.entity_identity.source_id} is {@code NOT NULL} (part of its PK, V49) — the
   * upsert throws, uncaught, aborting every row {@link EntityResolutionPass} had not yet reached in
   * this call, not just the bad one.
   */
  @Test
  void incremental_pass_skips_a_null_identity_row_without_aborting_the_rest_of_the_run() throws Exception {
    long tenant = freshTenant();
    UUID runId = insertConnectorRun(tenant, 2, 2, 0);
    insertRow(tenant, runId, null, "orphan@example.com"); // NULL declared identity column
    insertRow(tenant, runId, "inc-null-2", "inc-null-2@example.com"); // must still resolve

    assertThatCode(() -> pass.runIncremental(tenant, runId)).doesNotThrowAnyException();

    assertThat(readIdentity(tenant, "inc-null-2").state()).isEqualTo("resolved");
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT count(*) FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ?")) {
      ps.setLong(1, tenant);
      ps.setString(2, ENTITY_TYPE);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).as("only the valid row gets a crosswalk row").isEqualTo(1L);
      }
    }
  }

  /**
   * {@code resolveByRunId} lacked the "latest physical row per identity" dedup that
   * {@code latestPerIdentitySql} (retry/replay) already carries. Two
   * content-changed physical rows for the SAME {@code source_id} within the SAME run both carry
   * that run's {@code _run_id} (design doc §7) — if the run wrote a stale value before a correction
   * (a paginated source with an inconsistent snapshot, a late-arriving fix mid-extraction), the
   * OLDER row is processed first, fresh-mints an entity, and the NEWER row's differently-computed
   * resolution is then refused by {@link CascadeResolver}'s "never silently recycle" guard —
   * {@code ambiguous} purely from intra-run processing order, not any genuine external conflict.
   */
  @Test
  void incremental_pass_resolves_only_the_latest_of_two_same_run_rows_for_one_identity() throws Exception {
    long tenant = freshTenant();
    UUID runId = insertConnectorRun(tenant, 2, 2, 0);
    insertRow(tenant, runId, "flip-run-1", "stale-in-run@example.com");
    insertRow(tenant, runId, "flip-run-1", "corrected-in-run@example.com"); // same source_id, same run

    pass.runIncremental(tenant, runId);

    CrosswalkRow row = readIdentity(tenant, "flip-run-1");
    assertThat(row.state())
        .as("only the run's LATEST content for this identity should ever be resolved")
        .isEqualTo("resolved");
    assertThat(row.entityId()).isNotNull();
  }

  /**
   * Failure mode: provider unavailable → {@code unresolved_provider}; a later pass retries only
   * those. Also proves retry re-reads the LATEST physical row for the identity, not the stale one
   * that first produced the outage — {@code source_id} is stable across content changes (design doc
   * §7), so a re-ingested row with the email now filled in is a fresh {@code INSERT}, not an UPDATE.
   *
   * <p>Uses the {@code propose:}-fallback manifest with a pre-seeded, already-resolved anchor: a
   * cold cascade behind a {@code propose:} rule always ends {@code unresolved_provider} today (no
   * {@code CandidateProvider} seam yet, #621) — the only way the email rule can ever find an
   * existing candidate to accept is a row some OTHER means already resolved.
   */
  @Test
  void a_provider_unavailable_row_is_retried_by_a_later_pass_and_resolves() throws Exception {
    long tenant = freshTenant();
    UUID anchorEntity = UUID.randomUUID();
    // A DIFFERENT source than TABLE_PROVIDER: v1 cardinality (design doc §3) allows a canonical
    // entity at most one identity per source, so the anchor and "retry-1" (same source table)
    // could never share an entity_id — exactly the shape a second, already-fused source provides.
    seedResolvedAnchor(ENTITY_TYPE_PROVIDER, tenant, "resit620b-anchor-src", "anchor-1", anchorEntity,
        "retry1@example.com");

    UUID runId = insertConnectorRun(tenant, 1, 1, 0);
    insertRow(TABLE_PROVIDER, tenant, runId, "retry-1", null); // no email → falls through to propose

    pass.runIncremental(tenant, runId);
    assertThat(readIdentity(ENTITY_TYPE_PROVIDER, TABLE_PROVIDER, tenant, "retry-1").state())
        .isEqualTo("unresolved_provider");

    // The source is re-ingested with the email now present — a NEW physical row, same source_id.
    insertRow(TABLE_PROVIDER, tenant, null, "retry-1", "retry1@example.com");

    pass.runRetry(tenant, "ResIt620b");

    CrosswalkRow after = readIdentity(ENTITY_TYPE_PROVIDER, TABLE_PROVIDER, tenant, "retry-1");
    assertThat(after.state()).isEqualTo("resolved");
    assertThat(after.entityId()).isEqualTo(anchorEntity);
  }

  /**
   * Failure mode: a rule-version change (or, equivalently here, a data change surfaced only on
   * replay) must never silently move an already-resolved canonical id — refused and recorded
   * {@code ambiguous} instead (design doc §10; the guard itself lives in {@link CascadeResolver},
   * this proves the PASS's replay path actually drives a row through it).
   */
  @Test
  void a_replay_never_silently_moves_an_existing_canonical_id() throws Exception {
    long tenant = freshTenant();
    insertRow(tenant, null, "stable-1", "shared@example.com");
    insertRow(tenant, null, "stable-2", "other@example.com");
    pass.runReplay(tenant, "ResIt620");
    UUID entity1 = readIdentity(tenant, "stable-1").entityId();
    UUID entity2 = readIdentity(tenant, "stable-2").entityId();
    assertThat(entity1).isNotNull();
    assertThat(entity2).isNotNull().isNotEqualTo(entity1);

    // stable-2's email now collides with stable-1's — re-evaluation would otherwise merge them.
    // stable-1 sorts first (keyset order), so it is re-evaluated first and stays exactly as it
    // was; stable-2 is the row whose OWN link would move, and that move is what must be refused.
    insertRow(tenant, null, "stable-2", "shared@example.com");
    pass.runReplay(tenant, "ResIt620");

    CrosswalkRow row1 = readIdentity(tenant, "stable-1");
    assertThat(row1.state()).as("the row nothing changed about must stay exactly as it was")
        .isEqualTo("resolved");
    assertThat(row1.entityId()).isEqualTo(entity1);

    CrosswalkRow row2 = readIdentity(tenant, "stable-2");
    assertThat(row2.state()).as("a resolved link must never be silently swapped").isEqualTo("ambiguous");
    assertThat(row2.entityId()).isNull();
  }

  /**
   * A stale intermediate physical row must never be resolved at all — not merely superseded.
   * Reprocessing it would fresh-mint an entity from data the identity no longer carries, which
   * then makes the identity's OWN later, legitimate resolution look like an attempted silent
   * recycle of a "prior" link nobody ever actually decided on — the guard would refuse a perfectly
   * good resolution because of a phantom intermediate one (design doc §7's "latest row, never a
   * stale one").
   */
  @Test
  void a_replay_resolves_only_the_latest_content_never_a_superseded_intermediate_row() throws Exception {
    long tenant = freshTenant();
    // A DIFFERENT source than TABLE: v1 cardinality (design doc §3) allows a canonical entity at
    // most one identity per source, so this anchor and "flip-1" (same source table) could never
    // legitimately share an entity_id otherwise.
    UUID anchorEntity = UUID.randomUUID();
    seedResolvedAnchor(ENTITY_TYPE, tenant, "resit620-anchor-src", "anchor-flip", anchorEntity,
        "correct@example.com");

    // "flip-1" is first ingested with a throwaway email (no match anywhere — would fresh-mint if
    // ever resolved), then immediately corrected to the anchor's email — a NEW physical row, same
    // source_id. Only the correction should ever be resolved.
    insertRow(tenant, null, "flip-1", "throwaway@example.com");
    insertRow(tenant, null, "flip-1", "correct@example.com");
    pass.runReplay(tenant, "ResIt620");

    CrosswalkRow flip = readIdentity(tenant, "flip-1");
    assertThat(flip.state())
        .as("only the latest row's data should ever have been resolved").isEqualTo("resolved");
    assertThat(flip.entityId()).isEqualTo(anchorEntity);
  }

  @Test
  void two_consecutive_replay_passes_produce_no_crosswalk_changes() throws Exception {
    long tenant = freshTenant();
    insertRow(tenant, null, "idem-1", "idem1@example.com");
    insertRow(tenant, null, "idem-2", "idem2@example.com");

    pass.runReplay(tenant, "ResIt620");
    CrosswalkRow first1 = readIdentity(tenant, "idem-1");
    CrosswalkRow first2 = readIdentity(tenant, "idem-2");

    pass.runReplay(tenant, "ResIt620");
    CrosswalkRow second1 = readIdentity(tenant, "idem-1");
    CrosswalkRow second2 = readIdentity(tenant, "idem-2");

    assertThat(second1).isEqualTo(first1);
    assertThat(second2).isEqualTo(first2);
  }

  @Test
  void a_replay_never_touches_another_tenants_identically_valued_row() throws Exception {
    long t1 = freshTenant();
    long t2 = freshTenant();
    String sharedRawId = "shared-tenant-id";
    insertRow(t1, null, sharedRawId, "t1@example.com");
    insertRow(t2, null, sharedRawId, "t2@example.com");

    pass.runReplay(t1, "ResIt620");

    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT 1 FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ? "
                 + "AND source = ? AND source_id = ?")) {
      ps.setLong(1, t2);
      ps.setString(2, ENTITY_TYPE);
      ps.setString(3, TABLE);
      ps.setString(4, sharedRawId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("replaying tenant 1 must not resolve tenant 2's row").isFalse();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // icreated's review on PR #645 (#622): every trigger must log its own summary — not just
  // runIncremental (the automatic, most-common one already fixed once) — and rows_seen must be
  // named explicitly, not left implicit in the other four counters' sum.
  // ---------------------------------------------------------------------------------------------

  @Test
  void incremental_pass_logs_its_summary_with_rows_seen() throws Exception {
    long tenant = freshTenant();
    UUID runId = insertConnectorRun(tenant, 1, 1, 0);
    insertRow(tenant, runId, "log-inc-1", "log-inc-1@example.com");

    List<LogProbe.Line> lines = LogProbe.capture(EntityResolutionPass.class,
        () -> pass.runIncremental(tenant, runId));

    // Two concurrent runs of the same tenant are only distinguishable by runId — dropping it
    // from the message (Sergey's review on PR #645) would make them indistinguishable in the log.
    assertThat(lines).anySatisfy(l -> assertThat(l.message())
        .contains("rows_seen=1").contains(runId.toString()));
  }

  @Test
  void replay_pass_logs_its_summary_with_rows_seen() throws Exception {
    long tenant = freshTenant();
    insertRow(tenant, null, "log-replay-1", "log-replay-1@example.com");

    List<LogProbe.Line> lines = LogProbe.capture(EntityResolutionPass.class,
        () -> pass.runReplay(tenant, "ResIt620"));

    assertThat(lines).anySatisfy(l -> assertThat(l.message()).contains("rows_seen=1"));
  }

  @Test
  void retry_pass_logs_its_summary_with_rows_seen() throws Exception {
    long tenant = freshTenant();
    UUID anchorEntity = UUID.randomUUID();
    seedResolvedAnchor(ENTITY_TYPE_PROVIDER, tenant, "resit620b-anchor-log", "anchor-log", anchorEntity,
        "retrylog@example.com");
    UUID runId = insertConnectorRun(tenant, 1, 1, 0);
    insertRow(TABLE_PROVIDER, tenant, runId, "retry-log-1", null);
    pass.runIncremental(tenant, runId);
    insertRow(TABLE_PROVIDER, tenant, null, "retry-log-1", "retrylog@example.com");

    List<LogProbe.Line> lines = LogProbe.capture(EntityResolutionPass.class,
        () -> pass.runRetry(tenant, "ResIt620b"));

    assertThat(lines).anySatisfy(l -> assertThat(l.message()).contains("rows_seen=1"));
  }
}

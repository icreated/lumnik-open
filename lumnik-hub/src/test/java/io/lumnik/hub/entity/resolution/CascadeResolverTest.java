package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.entity.CandidateProvider;
import io.lumnik.extraction.spi.entity.CandidateProviderException;
import io.lumnik.extraction.spi.entity.CandidateQuery;
import io.lumnik.extraction.spi.entity.ProposedCandidate;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot #617 of the #613 entity-resolution arc (design doc §7): the native ordered cascade and the
 * decision policy. Exercises {@link CascadeResolver#resolve} end to end against real Postgres
 * (crosswalk + fingerprint + evidence tables from #615, fingerprints from #616) rather than mocking
 * the persistence it decides — a mocked crosswalk cannot prove the candidate-collection query
 * actually distinguishes "one entity, several source rows" from "two entities, one rule".
 */
@QuarkusTest
class CascadeResolverTest {

  @Inject CascadeResolver resolver;
  @Inject EntityIdentityRepository repository;
  @Inject SearchProjectionMarkStore markStore;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "customer";
  private static final AtomicLong NEXT_TENANT = new AtomicLong(20_000);

  private long freshTenant() {
    return NEXT_TENANT.getAndIncrement();
  }

  private static EntityManifest.CascadeRule exactRule(String id, List<String> fields, List<String> normalize) {
    return new EntityManifest.CascadeRule(id, new EntityManifest.Exact(fields, normalize), null);
  }

  private static EntityManifest.CascadeRule proposeRule(String id, String provider) {
    return new EntityManifest.CascadeRule(id, null, new EntityManifest.Propose(provider, 0.95, 0.80));
  }

  /**
   * A resolver wired with exactly the given candidate providers, built by hand rather than
   * {@code @Inject}ed: registering a real CDI bean for {@link CandidateProvider} (e.g. via
   * {@code @InjectMock}) would make {@code Instance<CandidateProvider>} satisfied for every test in
   * this class, including {@link #a_propose_rule_with_no_provider_available_yields_unresolved_provider}
   * above, which relies on it staying empty. {@link CascadeResolver#fingerprints} and {@link
   * CascadeResolver#repository} are still the real, CDI-injected beans, so this still runs against
   * real Postgres exactly like every other test here.
   */
  private CascadeResolver resolverWith(CandidateProvider... available) {
    CascadeResolver r = new CascadeResolver();
    r.fingerprints = fingerprintService;
    r.repository = repository;
    r.markStore = markStore;
    @SuppressWarnings("unchecked")
    Instance<CandidateProvider> providers = Mockito.mock(Instance.class);
    Mockito.when(providers.iterator()).thenAnswer(inv -> List.of(available).iterator());
    r.providers = providers;
    return r;
  }

  // ---------------------------------------------------------------------------------------------
  // Seeding helpers — direct SQL against the admin (RLS-bypassing) datasource, mirroring
  // EntityResolutionSchemaRlsIT's idiom, since these tests seed states the cascade itself cannot
  // produce in one call (a prior run's outcome).
  // ---------------------------------------------------------------------------------------------

  private UUID seedResolvedIdentity(long tenant, String source, String sourceId, UUID entityId) {
    UUID identityRef = UUID.randomUUID();
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity "
          + "(tenant_id, entity_type, source, source_id, identity_ref, state, entity_id, decided_by, decided_at) "
          + "VALUES (?, ?, ?, ?, ?, 'resolved', ?, 'automatic', now())")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setObject(5, identityRef);
        ps.setObject(6, entityId);
        ps.executeUpdate();
      }
    });
    return identityRef;
  }

  private void seedKey(long tenant, String source, String sourceId, RuleIdentity identity, byte[] fingerprint) {
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity_key "
          + "(tenant_id, entity_type, source, source_id, rule_declared_id, rule_version, key_fingerprint) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setString(5, identity.ruleId());
        ps.setString(6, identity.ruleVersion());
        ps.setBytes(7, fingerprint);
        ps.executeUpdate();
      }
    });
  }

  /** Seeds a fully-resolved source row: the crosswalk row AND its matching fingerprint key. */
  private void seedResolvedRow(long tenant, String source, String sourceId, UUID entityId,
      EntityManifest.CascadeRule rule, Map<String, String> rawFieldValues) {
    seedResolvedIdentity(tenant, source, sourceId, entityId);
    RuleIdentity identity = ruleIdentityOf(rule);
    byte[] fp = fingerprintOf(tenant, rule, rawFieldValues).orElseThrow();
    seedKey(tenant, source, sourceId, identity, fp);
  }

  /**
   * Seeds a row as already resolved via a human assertion — {@code decided_by='asserted'} — with a
   * matching {@code core.entity_resolution_evidence} row carrying {@code assertedPartnerRef}, and
   * deliberately NO {@code entity_identity_key} row (mirrors {@link #seedResolvedIdentity}: nothing
   * for the row to self-match against on a later {@code resolve()} call). {@code assertedPartnerRef}
   * must already exist as a real {@code identity_ref} in {@code core.entity_identity} — the V59 FK
   * requires it.
   */
  private UUID seedAssertedResolvedIdentity(long tenant, String source, String sourceId, UUID entityId,
      UUID assertedPartnerRef) {
    UUID identityRef = seedResolvedIdentity(tenant, source, sourceId, entityId);
    markAsserted(tenant, source, sourceId, identityRef, entityId, assertedPartnerRef);
    return identityRef;
  }

  /** Runs {@code first} then {@code second}, or the reverse: the reprocessing order under test. */
  private static void runInOrder(boolean firstThenSecond, Runnable first, Runnable second) {
    if (firstThenSecond) {
      first.run();
      second.run();
    } else {
      second.run();
      first.run();
    }
  }

  /**
   * Turns an already-seeded resolved row into an asserted one: {@code decided_by='asserted'} plus its
   * evidence row naming {@code assertedPartnerRef} (which must already exist — the V59 FK).
   */
  private void markAsserted(long tenant, String source, String sourceId, UUID identityRef, UUID entityId,
      UUID assertedPartnerRef) {
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("UPDATE core.entity_identity SET decided_by = 'asserted' "
          + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.executeUpdate();
      }
    });
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_resolution_evidence "
          + "(tenant_id, entity_type, source, source_id, identity_ref, candidates, outcome, decided_by, "
          + "asserted_partner_ref) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'resolved', 'asserted', ?)")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setObject(5, identityRef);
        ps.setString(6, "[\"" + entityId + "\"]");
        ps.setObject(7, assertedPartnerRef);
        ps.executeUpdate();
      }
    });
  }

  @Inject EntityFingerprintService fingerprintService;

  private RuleIdentity ruleIdentityOf(EntityManifest.CascadeRule rule) {
    EntityManifest.Exact ex = rule.exact();
    return fingerprintService.ruleIdentity(rule.id(), ex.fields(), ex.normalize());
  }

  private Optional<byte[]> fingerprintOf(long tenant, EntityManifest.CascadeRule rule, Map<String, String> rawFieldValues) {
    EntityManifest.Exact ex = rule.exact();
    return fingerprintService.fingerprint(tenant, ENTITY_TYPE, rule.id(), ex.fields(), ex.normalize(), rawFieldValues);
  }

  private void exec(SqlWork work) {
    try (Connection c = adminDs.getConnection()) {
      work.run(c);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws Exception;
  }

  private String stateOf(long tenant, String source, String sourceId) {
    return queryOne(tenant, source, sourceId, "state", ResultSet::getString);
  }

  private UUID entityIdOf(long tenant, String source, String sourceId) {
    return queryOne(tenant, source, sourceId, "entity_id", (rs, col) -> (UUID) rs.getObject(col));
  }

  private UUID identityRefOf(long tenant, String source, String sourceId) {
    return queryOne(tenant, source, sourceId, "identity_ref", (rs, col) -> (UUID) rs.getObject(col));
  }

  @FunctionalInterface
  private interface ColumnReader<T> {
    T read(ResultSet rs, String column) throws Exception;
  }

  private <T> T queryOne(long tenant, String source, String sourceId, String column, ColumnReader<T> reader) {
    Object[] holder = new Object[1];
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM core.entity_identity "
          + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("entity_identity row for " + source + "/" + sourceId).isTrue();
          holder[0] = reader.read(rs, column);
        }
      }
    });
    @SuppressWarnings("unchecked")
    T value = (T) holder[0];
    return value;
  }

  private long countKeyRows(long tenant, String source, String sourceId) {
    return countRows("SELECT count(*) FROM core.entity_identity_key "
        + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?",
        tenant, ENTITY_TYPE, source, sourceId);
  }

  private long countEvidenceRows(long tenant, String source, String sourceId, String ruleDeclaredId, String outcome) {
    return countRows("SELECT count(*) FROM core.entity_resolution_evidence "
        + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ? "
        + "AND rule_declared_id IS NOT DISTINCT FROM ? AND outcome = ?",
        tenant, ENTITY_TYPE, source, sourceId, ruleDeclaredId, outcome);
  }

  private long countRows(String sql, Object... params) {
    long[] holder = {-1};
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          holder[0] = rs.getLong(1);
        }
      }
    });
    return holder[0];
  }

  private UUID seedUnresolvedIdentity(long tenant, String source, String sourceId) {
    UUID identityRef = UUID.randomUUID();
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity "
          + "(tenant_id, entity_type, source, source_id, identity_ref, state, entity_id, decided_by, decided_at) "
          + "VALUES (?, ?, ?, ?, ?, 'unresolved_no_rule', NULL, 'automatic', now())")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setObject(5, identityRef);
        ps.executeUpdate();
      }
    });
    return identityRef;
  }

  private String decidedByOf(long tenant, String source, String sourceId) {
    return queryOne(tenant, source, sourceId, "decided_by", ResultSet::getString);
  }

  private String evidenceDecidedBy(long tenant, String source, String sourceId, String outcome) {
    Object[] holder = new Object[1];
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("SELECT decided_by FROM core.entity_resolution_evidence "
          + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ? AND outcome = ?")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setString(5, outcome);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("evidence row for outcome " + outcome).isTrue();
          holder[0] = rs.getString(1);
        }
      }
    });
    return (String) holder[0];
  }

  /** The evidence row's raw JSON text for {@code column} ({@code candidates} or {@code
   * discarded_candidates}), for the single row matching this outcome. */
  private String evidenceJsonColumn(String column, long tenant, String source, String sourceId, String outcome) {
    Object[] holder = new Object[1];
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("SELECT " + column + "::text FROM core.entity_resolution_evidence "
          + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ? AND outcome = ?")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setString(5, outcome);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("evidence row for outcome " + outcome).isTrue();
          holder[0] = rs.getString(1);
        }
      }
    });
    return (String) holder[0];
  }

  private String evidenceCandidatesJson(long tenant, String source, String sourceId, String outcome) {
    return evidenceJsonColumn("candidates", tenant, source, sourceId, outcome);
  }

  private String evidenceDiscardedCandidatesJson(long tenant, String source, String sourceId, String outcome) {
    return evidenceJsonColumn("discarded_candidates", tenant, source, sourceId, outcome);
  }

  // ---------------------------------------------------------------------------------------------
  // The four outcomes (design doc §7).
  // ---------------------------------------------------------------------------------------------

  @Test
  void an_unmatched_row_with_a_produced_key_mints_a_new_resolved_entity() {
    long tenant = freshTenant();
    String sourceId = "cust-" + UUID.randomUUID();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", sourceId,
        List.of(email), Map.of("email", "Alice@Example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isNotNull();
    assertThat(stateOf(tenant, "erp", sourceId)).isEqualTo("resolved");
    assertThat(entityIdOf(tenant, "erp", sourceId)).isEqualTo(result.entityId());
    assertThat(countKeyRows(tenant, "erp", sourceId)).isEqualTo(1);
    assertThat(countEvidenceRows(tenant, "erp", sourceId, null, "resolved")).isEqualTo(1);
    // #622's observability (icreated's review on PR #645): a fresh mint still means "email"
    // computed a usable key and evaluated this row — reporting it as inactive here is exactly
    // the "active rule looks dead" defect the summary exists to catch. newlyMinted still says
    // this wasn't an ordinary match.
    assertThat(result.newlyMinted()).isTrue();
    assertThat(result.ruleIdsInvolved()).containsExactly(ENTITY_TYPE + ":email");
  }

  @Test
  void a_row_matching_one_existing_resolved_entity_resolves_to_it_not_a_new_mint() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID existing = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", existing, email, Map.of("email", "bob@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "Bob@Example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(existing);
    assertThat(countEvidenceRows(tenant, "crm", "contact-9", "email", "resolved")).isEqualTo(1);
    assertThat(result.newlyMinted()).isFalse();
    assertThat(result.ruleIdsInvolved()).containsExactly(ENTITY_TYPE + ":email");
  }

  /**
   * icreated's review on PR #645, part 2: a bare rule id in {@code ruleIdsInvolved} would make a
   * whole-tenant pass (which fans out over every resolution-bearing manifest into ONE shared
   * {@code PassSummary}) merge two unrelated entities' same-named {@code email} rule into one
   * bucket — a rule that never fires on "supplier" would be hidden by "customer"'s own healthy
   * count of the identically-named rule. Qualifying by entity type keeps them apart.
   */
  @Test
  void rule_ids_are_qualified_by_entity_type_so_same_named_rules_never_collide_in_a_shared_tally() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));

    ResolutionResult customerResult = resolver.resolve(tenant, "customer", "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    ResolutionResult supplierResult = resolver.resolve(tenant, "supplier", "erp", "sup-1",
        List.of(email), Map.of("email", "bob@example.com"), null);

    assertThat(customerResult.ruleIdsInvolved()).containsExactly("customer:email");
    assertThat(supplierResult.ruleIdsInvolved()).containsExactly("supplier:email");
  }

  @Test
  void two_distinct_entities_sharing_a_fingerprint_yield_ambiguous_never_a_merge() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID aliceOne = UUID.randomUUID();
    UUID aliceTwo = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", aliceOne, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "crm", "contact-1", aliceTwo, email, Map.of("email", "shared@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1",
        List.of(email), Map.of("email", "shared@example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    assertThat(stateOf(tenant, "web", "lead-1")).isEqualTo("ambiguous");
    assertThat(countEvidenceRows(tenant, "web", "lead-1", "email", "ambiguous")).isEqualTo(1);
    // An ambiguous outcome IS a rule hit — the rule found candidates, just more than one.
    assertThat(result.ruleIdsInvolved()).containsExactly(ENTITY_TYPE + ":email");
  }

  @Test
  void many_source_rows_sharing_one_entity_are_not_ambiguous() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID shared = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", shared, email, Map.of("email", "same@example.com"));
    seedResolvedRow(tenant, "crm", "contact-1", shared, email, Map.of("email", "same@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1",
        List.of(email), Map.of("email", "same@example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(shared);
  }

  @Test
  void a_later_more_specific_rule_arbitrates_after_an_earlier_ambiguity_and_the_ledger_keeps_it() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    EntityManifest.CascadeRule civilIdentity =
        exactRule("civil-identity", List.of("last_name", "first_name"), List.of("clean", "lower"));
    UUID aliceMartin = UUID.randomUUID();
    UUID bobMartin = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", aliceMartin, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "crm", "contact-1", bobMartin, email, Map.of("email", "shared@example.com"));
    // Same crosswalk row as above (erp/cust-1) — add its civil-identity key without re-inserting the identity.
    seedKey(tenant, "erp", "cust-1", ruleIdentityOf(civilIdentity),
        fingerprintOf(tenant, civilIdentity, Map.of("last_name", "Martin", "first_name", "Alice")).orElseThrow());

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", List.of(email, civilIdentity),
        Map.of("email", "shared@example.com", "last_name", "Martin", "first_name", "Alice"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(aliceMartin);
    // the earlier ambiguity stays in the ledger — nothing merges silently, everything is explained.
    assertThat(countEvidenceRows(tenant, "web", "lead-1", "email", "ambiguous")).isEqualTo(1);
    assertThat(countEvidenceRows(tenant, "web", "lead-1", "civil-identity", "resolved")).isEqualTo(1);
  }

  @Test
  void no_rule_can_produce_a_key_yields_unresolved_no_rule() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-blank",
        List.of(email), Map.of("email", "   "), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(result.entityId()).isNull();
    assertThat(stateOf(tenant, "erp", "cust-blank")).isEqualTo("unresolved_no_rule");
    assertThat(countKeyRows(tenant, "erp", "cust-blank")).isEqualTo(0);
    assertThat(countEvidenceRows(tenant, "erp", "cust-blank", null, "unresolved_no_rule")).isEqualTo(1);
  }

  @Test
  void a_propose_rule_with_no_provider_available_yields_unresolved_provider() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_PROVIDER);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "unresolved_provider")).isEqualTo(1);
    // #622's observability: no bean wired is not a CALL — there is nothing to have called.
    assertThat(result.providerCalls()).isZero();
    assertThat(result.providerFailures()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // #621 — the CandidateProvider seam.
  // ---------------------------------------------------------------------------------------------

  private static CandidateProvider throwing(String name) {
    return new CandidateProvider() {
      public String name() { return name; }
      public List<ProposedCandidate> propose(CandidateQuery query) {
        throw new CandidateProviderException(name + " is unreachable");
      }
    };
  }

  @Test
  void a_propose_rule_whose_provider_throws_yields_unresolved_provider() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithThrowingProvider = resolverWith(throwing("elasticsearch"));

    ResolutionResult result = resolverWithThrowingProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_PROVIDER);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "unresolved_provider")).isEqualTo(1);
    // #622's observability: a wired provider that throws IS a call, and a failed one.
    assertThat(result.providerCalls()).isEqualTo(1);
    assertThat(result.providerFailures()).isEqualTo(1);
  }

  @Test
  void a_provider_throwing_any_runtime_exception_still_yields_unresolved_provider() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CandidateProvider buggy = new CandidateProvider() {
      public String name() { return "elasticsearch"; }
      // Deliberately NOT a CandidateProviderException — a buggy or misconfigured provider can
      // throw anything, and the posture ("absent or throwing -> unresolved_provider") must hold
      // for every RuntimeException, not only the one type the SPI declares.
      public List<ProposedCandidate> propose(CandidateQuery query) {
        throw new IllegalStateException("index client not initialized");
      }
    };
    CascadeResolver resolverWithProvider = resolverWith(buggy);

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_PROVIDER);
    assertThat(result.entityId()).isNull();
  }

  @Test
  void a_providers_query_does_not_expose_the_resolvers_own_live_field_map_for_mutation() {
    long tenant = freshTenant();
    Map<String, String> capturedByProvider = new HashMap<>();
    CandidateProvider mutating = new CandidateProvider() {
      public String name() { return "elasticsearch"; }
      public List<ProposedCandidate> propose(CandidateQuery query) {
        try {
          query.rawFieldValues().put("email", "corrupted-by-provider");
        } catch (RuntimeException ignored) {
          // expected once the map handed to a provider is no longer the resolver's own live copy.
        }
        capturedByProvider.putAll(query.rawFieldValues());
        return List.of();
      }
    };
    Map<String, String> callersOwnMap = new HashMap<>(Map.of("email", "alice@example.com"));
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(mutating);

    resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), callersOwnMap, null);

    assertThat(callersOwnMap).as("the caller's own map must never be mutated by a provider")
        .containsEntry("email", "alice@example.com");
    assertThat(capturedByProvider).as("the provider must never even observe its own attempted mutation")
        .containsEntry("email", "alice@example.com");
  }

  private static CandidateProvider proposing(String name, ProposedCandidate... candidates) {
    return new CandidateProvider() {
      public String name() { return name; }
      public List<ProposedCandidate> propose(CandidateQuery query) { return List.of(candidates); }
    };
  }

  @Test
  void a_single_strong_proposed_candidate_naming_an_existing_entity_resolves_to_it() {
    long tenant = freshTenant();
    UUID existingEntity = UUID.randomUUID();
    seedResolvedIdentity(tenant, "crm", "contact-existing", existingEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(existingEntity, 0.97)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(existingEntity);
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "resolved")).isEqualTo(1);
    assertThat(result.providerCalls()).isEqualTo(1);
    assertThat(result.providerFailures()).isZero();
    assertThat(result.newlyMinted()).isFalse();
    assertThat(result.ruleIdsInvolved()).containsExactly(ENTITY_TYPE + ":es-candidates");
  }

  @Test
  void a_proposed_candidate_naming_an_entity_absent_from_the_crosswalk_is_discarded_and_recorded() {
    long tenant = freshTenant();
    UUID existingEntity = UUID.randomUUID();
    UUID staleIndexEntity = UUID.randomUUID(); // never minted in this tenant's crosswalk.
    seedResolvedIdentity(tenant, "crm", "contact-existing", existingEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(proposing("elasticsearch",
        new ProposedCandidate(existingEntity, 0.97),
        new ProposedCandidate(staleIndexEntity, 0.99)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(existingEntity);
    assertThat(evidenceCandidatesJson(tenant, "erp", "cust-1", "resolved"))
        .as("only the valid candidate counts toward the decision")
        .isEqualTo("[\"" + existingEntity + "\"]");
    assertThat(evidenceDiscardedCandidatesJson(tenant, "erp", "cust-1", "resolved"))
        .contains(staleIndexEntity.toString())
        .contains("0.99");
  }

  @Test
  void a_provider_proposing_only_stale_candidates_still_persists_the_discard_and_retries_like_no_provider() {
    long tenant = freshTenant();
    UUID staleIndexEntity = UUID.randomUUID(); // never minted in this tenant's crosswalk.
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(staleIndexEntity, 0.99)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    // Nothing usable came out of this rule, but it is NOT the same as no provider being wired at
    // all: it must still be retried once the stale index is fixed, exactly like unresolved_provider
    // already is (#620's retry query targets that state) — so the discrepancy must survive.
    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_PROVIDER);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "unresolved_provider")).isEqualTo(1);
    assertThat(evidenceDiscardedCandidatesJson(tenant, "erp", "cust-1", "unresolved_provider"))
        .contains(staleIndexEntity.toString())
        .contains("0.99");
  }

  @Test
  void a_proposed_candidate_with_a_null_entity_id_is_discarded_rather_than_crashing() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(null, 0.99)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_PROVIDER);
    assertThat(result.entityId()).isNull();
  }

  @Test
  void a_single_review_band_candidate_forces_ambiguous_even_alone() {
    long tenant = freshTenant();
    UUID existingEntity = UUID.randomUUID();
    seedResolvedIdentity(tenant, "crm", "contact-existing", existingEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch"); // acceptAbove 0.95, reviewAbove 0.80
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(existingEntity, 0.85)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "ambiguous")).isEqualTo(1);
  }

  @Test
  void a_candidate_scored_below_reviewabove_is_dropped_silently_and_the_cascade_continues() {
    long tenant = freshTenant();
    UUID existingEntity = UUID.randomUUID();
    seedResolvedIdentity(tenant, "crm", "contact-existing", existingEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch"); // reviewAbove 0.80
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(existingEntity, 0.40)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    // Too weak to auto-link or even flag for review — this rule found nothing usable, same as an
    // exact: rule's own 0-candidate case: inconclusive, and with no other rule the row stays unresolved.
    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "ambiguous")).isEqualTo(0);
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "resolved")).isEqualTo(0);
  }

  @Test
  void two_strong_proposed_candidates_naming_different_entities_yield_ambiguous_never_a_merge() {
    long tenant = freshTenant();
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedIdentity(tenant, "crm", "contact-a", entityA);
    seedResolvedIdentity(tenant, "web", "lead-b", entityB);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(proposing("elasticsearch",
        new ProposedCandidate(entityA, 0.97), new ProposedCandidate(entityB, 0.96)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "es-candidates", "ambiguous")).isEqualTo(1);
  }

  @Test
  void a_distinct_assertion_vetoes_a_proposed_candidate_the_same_as_an_exact_rules_own_candidate() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "shared@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "shared mailbox, different people");

    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(entityA, 0.99)));

    // Without the assertion this would resolve straight to entityA — the veto must exclude it here
    // exactly as it already does for an exact: rule's own raw candidate list.
    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(result.entityId()).isNull();
  }

  @Test
  void a_distinct_veto_on_a_proposed_candidate_that_changes_the_outcome_is_recorded_as_asserted() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "shared@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "shared mailbox, different people");

    // Without the veto this would resolve straight to entityA — the veto is what makes it mint its
    // own instead, exactly as it already does for an exact: rule (same test shape as
    // a_distinct_veto_that_changes_the_outcome_is_recorded_as_asserted_not_automatic above).
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(entityA, 0.99)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");
  }

  @Test
  void duplicate_proposals_for_the_same_entity_do_not_manufacture_a_false_ambiguity() {
    long tenant = freshTenant();
    UUID existingEntity = UUID.randomUUID();
    seedResolvedIdentity(tenant, "crm", "contact-existing", existingEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    // A provider proposing the SAME entity twice (e.g. two matching documents in a search index)
    // must not count as two distinct candidates.
    CascadeResolver resolverWithProvider = resolverWith(proposing("elasticsearch",
        new ProposedCandidate(existingEntity, 0.97), new ProposedCandidate(existingEntity, 0.98)));

    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(existingEntity);
  }

  @Test
  void a_refused_swap_still_records_a_discarded_candidate_from_the_same_pass() {
    long tenant = freshTenant();
    UUID staleEntity = UUID.randomUUID(); // already resolved to THIS row, before re-evaluation.
    UUID otherEntity = UUID.randomUUID(); // what this pass's provider would swap to.
    UUID neverMintedEntity = UUID.randomUUID(); // never in the crosswalk — discarded.
    seedResolvedIdentity(tenant, "erp", "cust-1", staleEntity);
    seedResolvedIdentity(tenant, "crm", "contact-other", otherEntity);
    EntityManifest.CascadeRule propose = proposeRule("es-candidates", "elasticsearch");
    CascadeResolver resolverWithProvider = resolverWith(proposing("elasticsearch",
        new ProposedCandidate(otherEntity, 0.99), new ProposedCandidate(neverMintedEntity, 0.98)));

    // otherEntity alone would normally resolve and accept — but this row is already resolved to
    // staleEntity, so the guard refuses the swap (design doc §10) and the accepted event gets
    // replaced by the refusal's own event. The discarded neverMintedEntity from that SAME pass
    // must not disappear just because the accept got refused.
    ResolutionResult result = resolverWithProvider.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(propose), Map.of(), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(evidenceDiscardedCandidatesJson(tenant, "erp", "cust-1", "ambiguous"))
        .contains(neverMintedEntity.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // Idempotence and the "never silently recycle a resolved link" guard.
  // ---------------------------------------------------------------------------------------------

  @Test
  void replaying_the_same_row_and_the_same_rule_versions_produces_a_no_op_upsert() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));

    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "carol@example.com"), null);
    UUID identityRefAfterFirst = identityRefOf(tenant, "erp", "cust-1");

    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "carol@example.com"), null);

    assertThat(replay.state()).isEqualTo(first.state());
    assertThat(replay.entityId()).isEqualTo(first.entityId());
    assertThat(identityRefOf(tenant, "erp", "cust-1")).isEqualTo(identityRefAfterFirst);
    assertThat(countKeyRows(tenant, "erp", "cust-1")).isEqualTo(1);
  }

  @Test
  void re_evaluation_that_would_move_an_existing_resolved_entity_is_refused_and_recorded_ambiguous() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID staleEntity = UUID.randomUUID();
    UUID otherEntity = UUID.randomUUID();
    // This row was resolved under an earlier snapshot/rule version to staleEntity.
    seedResolvedIdentity(tenant, "erp", "cust-1", staleEntity);
    // Some unrelated, already-resolved row now matches what THIS row's fields would fingerprint to.
    seedResolvedRow(tenant, "crm", "contact-9", otherEntity, email, Map.of("email", "dana@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "dana@example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    assertThat(stateOf(tenant, "erp", "cust-1")).isEqualTo("ambiguous");
    assertThat(countEvidenceRows(tenant, "erp", "cust-1", "email", "ambiguous")).isEqualTo(1);
  }

  @Test
  void revoking_an_existing_resolution_to_unresolved_no_rule_still_records_the_lost_link_in_the_ledger() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID staleEntity = UUID.randomUUID();
    // This row was resolved under an earlier snapshot to staleEntity.
    seedResolvedIdentity(tenant, "erp", "cust-1", staleEntity);

    // The source system since cleared the field this row's only rule keys on.
    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "   "), null);

    // The crosswalk state is honestly unresolved_no_rule — not forced into ambiguous, since
    // nothing is being silently swapped to a wrong entity; the row simply lost its usable data.
    assertThat(result.state()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(result.entityId()).isNull();
    assertThat(stateOf(tenant, "erp", "cust-1")).isEqualTo("unresolved_no_rule");
    // But the ledger must not lose the fact this row used to resolve to staleEntity.
    assertThat(evidenceCandidatesJson(tenant, "erp", "cust-1", "unresolved_no_rule"))
        .contains(staleEntity.toString());
  }

  @Test
  void a_refused_swap_stays_refused_on_replay_even_though_the_row_is_now_ambiguous_not_resolved() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID otherEntity = UUID.randomUUID();
    seedResolvedRow(tenant, "crm", "contact-9", otherEntity, email, Map.of("email", "dana@example.com"));

    // First resolve this row for real (not a raw seed) so it has a genuine resolved history.
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(first.state()).isEqualTo(EntityResolutionState.RESOLVED);

    // A later pass's data would now match the unrelated, already-resolved otherEntity. Refused.
    ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "dana@example.com"), null);
    assertThat(second.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(second.entityId()).isNull();

    // Replaying the SAME (now-conflicting) data must not silently let it settle on otherEntity just
    // because the live crosswalk row is no longer "resolved" to compare against — only a human
    // assertion may move a canonical id (design doc §10), and that stays true across replays too.
    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "dana@example.com"), null);
    assertThat(replay.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(replay.entityId()).isNull();
  }

  @Test
  void a_field_that_becomes_unusable_has_its_stale_key_purged_so_it_cannot_haunt_a_future_row() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    EntityManifest.CascadeRule civilIdentity =
        exactRule("civil-identity", List.of("last_name", "first_name"), List.of("clean", "lower"));
    List<EntityManifest.CascadeRule> cascade = List.of(email, civilIdentity);

    // First pass: both rules produce a key on the same row, which mints a new entity.
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", cascade,
        Map.of("email", "alice@example.com", "last_name", "Martin", "first_name", "Alice"), null);
    assertThat(first.state()).isEqualTo(EntityResolutionState.RESOLVED);

    // Second pass: the source system cleared the email field, but civil-identity still matches
    // (itself), so the row stays resolved to the same entity.
    ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", cascade,
        Map.of("email", "   ", "last_name", "Martin", "first_name", "Alice"), null);
    assertThat(second.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(second.entityId()).isEqualTo(first.entityId());

    // A brand new, unrelated row now carries the OLD email value. Its own civil-identity fields
    // don't match anything, so only the (now stale) email key could possibly merge it — it must not.
    ResolutionResult unrelated = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", cascade,
        Map.of("email", "alice@example.com"), null);

    assertThat(unrelated.entityId()).isNotEqualTo(first.entityId());
  }

  @Test
  void a_later_rules_key_is_refreshed_even_when_an_earlier_rule_accepts_before_reaching_it() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    EntityManifest.CascadeRule civilIdentity =
        exactRule("civil-identity", List.of("last_name", "first_name"), List.of("clean", "lower"));
    List<EntityManifest.CascadeRule> cascade = List.of(email, civilIdentity);

    // First pass: both rules produce a key on the same row, which mints a new entity.
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", cascade,
        Map.of("email", "alice@example.com", "last_name", "Martin", "first_name", "Alice"), null);
    assertThat(first.state()).isEqualTo(EntityResolutionState.RESOLVED);

    // Second pass: the email is unchanged (so the email rule accepts on itself and the cascade
    // stops — civil-identity is never reached this pass), but the surname changed underneath.
    ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", cascade,
        Map.of("email", "alice@example.com", "last_name", "Dupont", "first_name", "Alice"), null);
    assertThat(second.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(second.entityId()).isEqualTo(first.entityId());

    // A brand new, unrelated row now carries the OLD surname and a distinct email. Only the (now
    // stale, never-reached-this-pass) civil-identity key could possibly merge it — it must not.
    ResolutionResult unrelated = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", cascade,
        Map.of("email", "someone-else@example.com", "last_name", "Martin", "first_name", "Alice"), null);

    assertThat(unrelated.entityId()).isNotEqualTo(first.entityId());
  }

  @Test
  void candidate_lookup_is_scoped_to_one_tenant() {
    long tenantA = freshTenant();
    long tenantB = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityInA = UUID.randomUUID();
    seedResolvedRow(tenantA, "erp", "cust-1", entityInA, email, Map.of("email", "erin@example.com"));

    ResolutionResult result = resolver.resolve(tenantB, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "erin@example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isNotEqualTo(entityInA);
  }

  // ---------------------------------------------------------------------------------------------
  // Human assertions — the correction path (#619, design doc §7: step 0, before any rule; a veto,
  // after any rule). An assertion only ever addresses a row the system has already seen (identity_ref
  // is minted on first upsert), so every test here seeds the row's identity first — exactly the
  // situation a human reviewer acts on in practice: a row the cascade already produced an opinion about.
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_same_assertion_links_two_identities_no_rule_matched() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person, different mailbox on file");

    // contact-9 carries no usable field for any declared rule — no rule could ever match it. The
    // assertion is the only reason it resolves at all.
    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "   "), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(entityA);
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");
    assertThat(countEvidenceRows(tenant, "crm", "contact-9", null, "resolved")).isEqualTo(1);
    assertThat(evidenceDecidedBy(tenant, "crm", "contact-9", "resolved")).isEqualTo("asserted");

    // Stays in effect on a second replay, not just the one call that applied it.
    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "   "), null);
    assertThat(replay.entityId()).isEqualTo(entityA);
  }

  @Test
  void a_same_assertion_outranks_a_rule_that_would_otherwise_resolve_differently() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityAsserted = UUID.randomUUID();
    UUID entityViaRule = UUID.randomUUID();
    UUID refA = seedResolvedIdentity(tenant, "erp", "cust-1", entityAsserted);
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");
    // Without the assertion, contact-9's own email would naturally match entityViaRule.
    seedResolvedRow(tenant, "web", "lead-1", entityViaRule, email, Map.of("email", "shared@example.com"));

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "shared@example.com"), null);

    // Step 0 outranks every rule (design doc §7): the cascade is never even consulted.
    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(entityAsserted);
  }

  @Test
  void retracting_a_same_assertion_lets_the_moved_row_split_back_off() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    // refA is the anchor: naturally, independently resolved to entityA.
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    // refB starts out naturally, independently resolved to its OWN entity via its own unique email
    // — nothing about refA and refB naturally matches each other.
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person, two mailboxes");
    ResolutionResult merged = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(merged.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(merged.entityId()).isEqualTo(entityA);
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted — different people after all");
    ResolutionResult split = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);

    assertThat(split.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(split.entityId()).isNotNull().isNotEqualTo(entityA);
    // The anchor is untouched — it was never itself moved by an assertion.
    assertThat(entityIdOf(tenant, "erp", "cust-1")).isEqualTo(entityA);
  }

  @Test
  void a_later_assertion_never_rewrites_who_asserted_an_earlier_decision_and_why() {
    // #1520: the history is an audit trail — each asserted line keeps the correction in force when it
    // was recorded, not the pair's current one.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    seedResolvedRow(tenant, "erp", "cust-1", UUID.randomUUID(), email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person, two mailboxes");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-2", "different people after all");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);

    List<EntityIdentityRepository.EvidenceEvent> asserted = repository.inTransaction(
            c -> repository.evidenceHistory(c, tenant, ENTITY_TYPE, "crm", "contact-9")).stream()
        .filter(e -> "asserted".equals(e.decidedBy())).toList();
    assertThat(asserted).extracting(EntityIdentityRepository.EvidenceEvent::assertedBy)
        .containsExactly("reviewer-1", "reviewer-2");
    assertThat(asserted).extracting(EntityIdentityRepository.EvidenceEvent::reason)
        .containsExactly("same person, two mailboxes", "different people after all");
  }

  @Test
  void an_anchor_reprocessed_while_the_merge_is_active_stays_automatic() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    ResolutionResult anchor = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);

    assertThat(anchor.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(anchor.entityId()).isEqualTo(entityA);
    // Only the row whose entity_id CHANGED was moved by the assertion.
    assertThat(decidedByOf(tenant, "erp", "cust-1")).isEqualTo("automatic");
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");
  }

  @Test
  void an_anchor_reprocessed_while_the_merge_is_active_keeps_the_rule_that_resolved_it() {
    // #1344: unchanged data, two passes — the anchor's ledger and the rule tally must not change.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-1", List.of(email), Map.of("email", "alice@example.com"), null);
    ResolutionResult byEmail = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    resolver.resolve(tenant, ENTITY_TYPE, "sav", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);

    resolver.assertSame(tenant, ENTITY_TYPE, identityRefOf(tenant, "erp", "cust-1"),
        identityRefOf(tenant, "sav", "contact-9"), "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "sav", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    ResolutionResult anchor = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);

    assertThat(anchor.entityId()).isEqualTo(byEmail.entityId());
    assertThat(anchor.ruleIdsInvolved()).isEqualTo(byEmail.ruleIdsInvolved()).containsExactly(ENTITY_TYPE + ":email");
    List<EntityIdentityRepository.EvidenceEvent> history = repository.inTransaction(
        c -> repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1"));
    assertThat(history).extracting(EntityIdentityRepository.EvidenceEvent::ruleDeclaredId)
        .containsExactly("email", "email");
    assertThat(history.get(1).ruleVersion()).isEqualTo(history.get(0).ruleVersion());
    assertThat(history.get(1).decidedBy()).isEqualTo("automatic");
  }

  @Test
  void the_moved_row_reprocessed_during_the_merge_keeps_asserted_provenance_so_its_split_stays_authorized() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    // A second pass over the moved row while the merge is active: it is already on entityA.
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    ResolutionResult split = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);

    assertThat(split.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(split.entityId()).isNotNull().isNotEqualTo(entityA);
  }

  @Test
  void a_same_assertion_redundant_with_a_rule_merge_marks_nobody_asserted() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(second.entityId()).isEqualTo(first.entityId());
    UUID ref1 = identityRefOf(tenant, "erp", "cust-1");
    UUID ref2 = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, ref1, ref2, "reviewer-1", "already the same");
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", List.of(email), Map.of("email", "alice@example.com"), null);
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "alice@example.com"), null);

    assertThat(decidedByOf(tenant, "erp", "cust-1")).isEqualTo("automatic");
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("automatic");
  }

  @Test
  void a_stale_assertion_that_was_never_retracted_still_refuses_into_ambiguous() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityE = UUID.randomUUID();
    UUID entityF = UUID.randomUUID();

    // Condition 1: the partner's OWN resolution state is non-resolved (unresolved_no_rule) — while
    // the assertion's verdict to it stays 'same', untouched. Without this, assertedSameEntityIds
    // would still find the partner and sameAssertionResolved would short-circuit the cascade before
    // revokesExistingResolution is ever reached, regardless of anything else in this test.
    UUID partnerRef = seedUnresolvedIdentity(tenant, "crm", "partner-1");
    UUID testedRef = seedAssertedResolvedIdentity(tenant, "erp", "target-1", entityE, partnerRef);
    resolver.assertSame(tenant, ENTITY_TYPE, testedRef, partnerRef, "reviewer-1", "seeded prior merge");

    // Condition 2: the tested row's own fingerprint must genuinely diverge from entityE once the
    // cascade runs — seedAssertedResolvedIdentity wrote no key for target-1, so there is nothing to
    // self-match; this fingerprints to a DIFFERENT, unrelated, already-resolved entity instead.
    seedResolvedRow(tenant, "web", "lead-9", entityF, email, Map.of("email", "carol@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "target-1",
        List.of(email), Map.of("email", "carol@example.com"), null);

    // decided_by='asserted' plus a STALE (never-retracted) assertion is never sufficient on its own
    // — only an explicit verdictOf == 'distinct' on the exact pair authorizes a split.
    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
  }

  @Test
  void two_different_asserted_partners_resolving_to_the_same_entity_do_not_let_one_retraction_split_the_row() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID sharedEntity = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", sharedEntity, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");
    // A second, independent identity that ALSO already resolves to sharedEntity.
    UUID refC = seedResolvedIdentity(tenant, "web", "lead-1", sharedEntity);

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person via cust-1");
    resolver.assertSame(tenant, ENTITY_TYPE, refC, refB, "reviewer-1", "same person via lead-1 too");
    ResolutionResult merged = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "   "), null);
    assertThat(merged.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(merged.entityId()).isEqualTo(sharedEntity);
    // The coincidence itself must not be mistaken for single-pair provenance: two DIFFERENT partner
    // refs both resolving to sharedEntity collapse assertedSameEntityIds to one id, but
    // assertedSamePartners stays at two — pinning this here is what would catch a regression that
    // wrongly keys assertedPartnerRef off assertedSameEntityIds.size() instead.
    EntityIdentityRepository.LastResolvedEvidence mergedEvidence = repository.inTransaction(
        c -> repository.lastResolvedEvidence(c, tenant, ENTITY_TYPE, "crm", "contact-9")).orElseThrow();
    // This assertion is the real regression pin for the coincidence case: the resolve()-outcome
    // assertions below pass even without it, since the still-valid refC~refB assertion keeps
    // sameAssertionResolved present on replay regardless of what assertedPartnerRef held.
    assertThat(mergedEvidence.assertedPartnerRef()).isNull();

    // Retract only ONE of the two same assertions — refC still vouches for sharedEntity.
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracting the cust-1 link only");
    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "   "), null);

    // Still resolved to sharedEntity through the still-valid refC~refB assertion — never split,
    // never ambiguous, and no provenance was ever attributable to a single pair to begin with.
    assertThat(replay.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(replay.entityId()).isEqualTo(sharedEntity);
  }

  @Test
  void a_split_events_ledger_entry_names_the_new_entity_first_not_the_departed_one() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    ResolutionResult split = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(split.state()).isEqualTo(EntityResolutionState.RESOLVED);

    // Direct repository read of the ledger, proving the fix independently of the live crosswalk row
    // (which would mask this bug — it always shows the correct current entity_id regardless).
    EntityIdentityRepository.LastResolvedEvidence lastEvidence = repository.inTransaction(
        c -> repository.lastResolvedEvidence(c, tenant, ENTITY_TYPE, "crm", "contact-9")).orElseThrow();
    assertThat(lastEvidence.entityId())
        .as("candidates ->> 0 must name the entity this row NOW resolves to, not the one it departed")
        .isEqualTo(split.entityId())
        .isNotEqualTo(entityA);
    // The departed entity is still audited — just not at position 0.
    assertThat(evidenceCandidatesJson(tenant, "crm", "contact-9", "resolved")).contains(entityA.toString());
  }

  @Test
  void retracting_a_same_assertion_can_let_the_row_settle_on_a_different_existing_entity_not_only_a_fresh_mint() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    UUID entityC = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");
    // A third, independent row that ALSO fingerprints to "bob@example.com" — the candidate
    // contact-9 naturally matches once the distinct veto strips its own (post-merge) self-match.
    seedResolvedRow(tenant, "web", "lead-9", entityC, email, Map.of("email", "bob@example.com"));

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    ResolutionResult split = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);

    // Settles on entityC (an existing entity, matched via the ordinary cascade) — never entityA
    // (the departed entity, vetoed as a distinct partner) and not a fresh mint either.
    assertThat(split.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(split.entityId()).isEqualTo(entityC);

    // The ledger-ordering fix (§4) must hold here too: candidates ->> 0 is the NEW entity.
    EntityIdentityRepository.LastResolvedEvidence lastEvidence = repository.inTransaction(
        c -> repository.lastResolvedEvidence(c, tenant, ENTITY_TYPE, "crm", "contact-9")).orElseThrow();
    assertThat(lastEvidence.entityId()).isEqualTo(entityC);
  }

  @Test
  void the_anchor_reprocessed_first_after_a_retraction_keeps_its_entity() {
    // icreated/lumnik#739: the anchor of a retracted same assertion keeps its own entity id even when
    // it is reprocessed before the moved row has caught up. The anchor's prior resolution comes from a
    // REAL resolver.resolve() call (decided_by='automatic', a genuine ledger row), not seedResolvedRow.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    ResolutionResult anchorFirstResolve = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(anchorFirstResolve.state()).isEqualTo(EntityResolutionState.RESOLVED);
    UUID entityA = anchorFirstResolve.entityId();
    UUID refA = identityRefOf(tenant, "erp", "cust-1");

    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    // refB now resolves to entityA. refA (the anchor) was never itself reprocessed.

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(first.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(first.entityId()).isEqualTo(entityA);

    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(replay.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(replay.entityId()).isEqualTo(entityA);

    ResolutionResult moved = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(moved.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(moved.entityId()).isNotNull().isNotEqualTo(entityA);
  }

  @Test
  void reprocessing_the_anchor_while_merged_never_makes_it_the_row_that_splits() {
    // icreated/lumnik#739: reprocessing the anchor while the merge is active leaves it 'automatic'
    // (an assertion only moves the row whose entity_id changes), so after a retraction it is never the
    // row that splits, whichever of the two rows reprocesses first.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    // Reprocess the ANCHOR too, while the assertion is still 'same' — its entity_id doesn't change
    // (it was already entityA), so it stays 'automatic' and picks up no asserted provenance.
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(decidedByOf(tenant, "erp", "cust-1")).isEqualTo("automatic");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    // Reprocess the anchor FIRST this time.
    ResolutionResult anchorResult = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    assertThat(anchorResult.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(anchorResult.entityId()).isEqualTo(entityA);

    ResolutionResult moved = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(moved.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(moved.entityId()).isNotNull().isNotEqualTo(entityA);
  }

  @Test
  void the_moved_row_can_still_split_even_after_the_anchor_has_already_gone_ambiguous() {
    // Regression (found in PR review of #737): the moved row's own split must not depend on the
    // anchor's CURRENT resolution state. the distinct veto only covers a partner that is
    // PRESENTLY 'resolved', so without a second, order-independent veto keyed on the row's OWN
    // explicit retraction, its stale self-match would slip through and the split would never happen.
    // Since #739 the anchor no longer goes ambiguous by the veto; a third row sharing the moved row's
    // email makes the anchor genuinely ambiguous through two candidates, independent of any veto.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    ResolutionResult anchorFirstResolve = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    UUID entityA = anchorFirstResolve.entityId();
    UUID refA = identityRefOf(tenant, "erp", "cust-1");

    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
    UUID refB = identityRefOf(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    // refB now resolves to entityA.

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");
    UUID entityC = UUID.randomUUID();
    seedResolvedRow(tenant, "web", "lead-9", entityC, email, Map.of("email", "bob@example.com"));
    ResolutionResult anchorAfter = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(anchorAfter.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);

    // The MOVED row's own explicit retraction must still take effect, regardless of the anchor's
    // now-ambiguous state (it may settle on entityC; its retraction vetoes entityA).
    ResolutionResult movedAfter = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(movedAfter.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(movedAfter.entityId()).isNotNull().isNotEqualTo(entityA);
  }

  @Test
  void after_a_retraction_both_rows_end_on_distinct_entities_whatever_the_reprocessing_order() {
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID entityA = UUID.randomUUID();
      UUID entityB = UUID.randomUUID();
      seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "erp", "cust-1");
      seedResolvedRow(tenant, "crm", "contact-9", entityB, email, Map.of("email", "bob@example.com"));
      UUID refB = identityRefOf(tenant, "crm", "contact-9");
      resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
      resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");

      Runnable anchor = () -> resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "bob@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      assertThat(entityIdOf(tenant, "erp", "cust-1")).as("anchor, anchorFirst=%s", anchorFirst).isEqualTo(entityA);
      assertThat(entityIdOf(tenant, "crm", "contact-9")).as("moved, anchorFirst=%s", anchorFirst)
          .isNotNull().isNotEqualTo(entityA);
    }
  }

  @Test
  void a_moved_row_that_is_itself_held_by_another_same_assertion_still_leaves_the_anchor_on_retraction() {
    // Final-review C1 (#739): A on E; P moved onto E by same(A,P); C moved onto E by same(P,C). After
    // distinct(A,P), A and P must never both stay RESOLVED on one entity, whatever the order.
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID entityE = UUID.randomUUID();
      seedResolvedRow(tenant, "erp", "cust-1", entityE, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "erp", "cust-1");
      seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
      UUID refP = identityRefOf(tenant, "crm", "contact-9");
      seedResolvedRow(tenant, "web", "lead-1", UUID.randomUUID(), email, Map.of("email", "carol@example.com"));
      UUID refC = identityRefOf(tenant, "web", "lead-1");

      resolver.assertSame(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "same person");
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refC, "reviewer-1", "same person too");
      resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", List.of(email), Map.of("email", "carol@example.com"), null);
      resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "retracted");

      ResolutionResult[] out = new ResolutionResult[2];
      Runnable anchor = () -> out[0] = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> out[1] = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "bob@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      boolean bothResolvedTogether = out[0].state() == EntityResolutionState.RESOLVED
          && out[1].state() == EntityResolutionState.RESOLVED
          && out[0].entityId().equals(out[1].entityId());
      assertThat(bothResolvedTogether).as("A and P resolved on one entity, anchorFirst=%s", anchorFirst).isFalse();
    }
  }

  @Test
  void a_moved_row_whose_other_same_partner_would_take_it_elsewhere_still_lets_the_anchor_keep_its_id() {
    // Re-review of #739: same(A,P) moved P onto E; same(P,C) with C resolved on G (C not reprocessed)
    // would take P OFF E, so the exemption must still apply and A keeps E in either order.
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID entityE = UUID.randomUUID();
      seedResolvedRow(tenant, "erp", "cust-1", entityE, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "erp", "cust-1");
      seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
      UUID refP = identityRefOf(tenant, "crm", "contact-9");
      seedResolvedRow(tenant, "web", "lead-1", UUID.randomUUID(), email, Map.of("email", "carol@example.com"));
      UUID refC = identityRefOf(tenant, "web", "lead-1");

      resolver.assertSame(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "same person");
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refC, "reviewer-1", "same person too");
      resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "retracted");

      ResolutionResult[] out = new ResolutionResult[2];
      Runnable anchor = () -> out[0] = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> out[1] = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "bob@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      assertThat(out[0].state()).as("anchor, anchorFirst=%s", anchorFirst).isEqualTo(EntityResolutionState.RESOLVED);
      assertThat(out[0].entityId()).as("anchor id, anchorFirst=%s", anchorFirst).isEqualTo(entityE);
      boolean shared = out[1].state() == EntityResolutionState.RESOLVED && entityE.equals(out[1].entityId());
      assertThat(shared).as("P stays on E, anchorFirst=%s", anchorFirst).isFalse();
    }
  }

  @Test
  void three_rows_reprocessed_anchor_then_other_partner_then_moved_leave_a_bounded_window_on_the_shared_entity() {
    // #743: same(A,P), same(P,C) with C on another entity G (unreprocessed), then distinct(A,P).
    // Reprocessing A, C, P: C adopts P's stale entity E via step 0 and holds P there, so A and P can sit
    // RESOLVED on E together until A's next pass, which the veto then settles. Pins the window as bounded.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityE = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityE, email, Map.of("email", "alice@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
    UUID refP = identityRefOf(tenant, "crm", "contact-9");
    seedResolvedRow(tenant, "web", "lead-1", UUID.randomUUID(), email, Map.of("email", "carol@example.com"));
    UUID refC = identityRefOf(tenant, "web", "lead-1");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "same person");
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    resolver.assertSame(tenant, ENTITY_TYPE, refP, refC, "reviewer-1", "same person too");
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "retracted");

    Runnable a = () -> resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    a.run();
    resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", List.of(email), Map.of("email", "carol@example.com"), null);
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
    assertThat(stateOf(tenant, "erp", "cust-1")).as("A after A, C, P").isEqualTo("resolved");
    assertThat(entityIdOf(tenant, "erp", "cust-1")).as("A shares E with P in the window").isEqualTo(entityE);
    assertThat(entityIdOf(tenant, "crm", "contact-9")).as("P held on E by C").isEqualTo(entityE);
    a.run();
    // The window closes on A's next pass, the same outcome as the held-row limit: P cannot leave, so A
    // is vetoed off E and surfaces for review rather than sharing E with P.
    assertThat(stateOf(tenant, "erp", "cust-1")).as("A after its second pass").isEqualTo("ambiguous");
    assertThat(entityIdOf(tenant, "crm", "contact-9")).as("P still on E").isEqualTo(entityE);
  }

  @Test
  void a_moved_row_whose_other_same_partners_resolve_to_several_entities_still_lets_the_anchor_keep_its_id() {
    // #743: same(A,P) moved P onto E; P is also asserted same with C1 (on G1) and C2 (on G2), neither
    // reprocessed. After distinct(A,P) the anchor keeps E in either order and P is never left on E with it.
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID entityE = UUID.randomUUID();
      seedResolvedRow(tenant, "erp", "cust-1", entityE, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "erp", "cust-1");
      seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
      UUID refP = identityRefOf(tenant, "crm", "contact-9");
      seedResolvedRow(tenant, "web", "lead-1", UUID.randomUUID(), email, Map.of("email", "carol@example.com"));
      UUID refC1 = identityRefOf(tenant, "web", "lead-1");
      seedResolvedRow(tenant, "shop", "acct-3", UUID.randomUUID(), email, Map.of("email", "erin@example.com"));
      UUID refC2 = identityRefOf(tenant, "shop", "acct-3");

      resolver.assertSame(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "same person");
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refC1, "reviewer-1", "same person too");
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refC2, "reviewer-1", "and this one");
      resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "retracted");

      ResolutionResult[] out = new ResolutionResult[2];
      Runnable anchor = () -> out[0] = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> out[1] = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "bob@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      assertThat(out[0].state()).as("anchor, anchorFirst=%s", anchorFirst).isEqualTo(EntityResolutionState.RESOLVED);
      assertThat(out[0].entityId()).as("anchor id, anchorFirst=%s", anchorFirst).isEqualTo(entityE);
      assertThat(out[1].state()).as("P held by two entities, anchorFirst=%s", anchorFirst)
          .isEqualTo(EntityResolutionState.AMBIGUOUS);
    }
  }

  @Test
  void a_row_healed_from_ambiguous_by_a_same_assertion_is_the_moved_row_and_can_split_on_retraction() {
    // Final-review I1 (#739): R was resolved on E, then refused into AMBIGUOUS (live entity_id NULL, the
    // ledger still says E). assertSame(R, A) with A on E moves R NULL -> E, so R is the moved row.
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID otherEntity = UUID.randomUUID();
      seedResolvedRow(tenant, "crm", "contact-9", otherEntity, email, Map.of("email", "dana@example.com"));
      UUID entityE = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null).entityId();
      UUID refR = identityRefOf(tenant, "erp", "cust-1");
      assertThat(resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "dana@example.com"), null).state())
          .isEqualTo(EntityResolutionState.AMBIGUOUS);
      seedResolvedRow(tenant, "web", "lead-1", entityE, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "web", "lead-1");

      resolver.assertSame(tenant, ENTITY_TYPE, refR, refA, "reviewer-1", "heal");
      ResolutionResult healed = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "dana@example.com"), null);
      assertThat(healed.entityId()).isEqualTo(entityE);
      EntityIdentityRepository.LastResolvedEvidence ev = repository.inTransaction(
          c -> repository.lastResolvedEvidence(c, tenant, ENTITY_TYPE, "erp", "cust-1")).orElseThrow();
      assertThat(ev.decidedBy()).as("healed row provenance").isEqualTo("asserted");
      assertThat(ev.assertedPartnerRef()).isEqualTo(refA);

      resolver.assertDistinct(tenant, ENTITY_TYPE, refR, refA, "reviewer-1", "retracted");
      Runnable anchor = () -> resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "dana@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      assertThat(entityIdOf(tenant, "web", "lead-1")).as("anchor, anchorFirst=%s", anchorFirst).isEqualTo(entityE);
      assertThat(entityIdOf(tenant, "erp", "cust-1")).as("moved, anchorFirst=%s", anchorFirst)
          .isNotNull().isNotEqualTo(entityE);
    }
  }

  @Test
  void pre_fix_mutual_asserted_provenance_sends_both_rows_off_the_shared_entity_on_retraction() {
    // Pre-#739 data: BOTH rows on the shared entity carry decided_by='asserted', each naming the other.
    // Pins the observed behaviour: the distinct assertion is still enforced, but neither row keeps the id.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID shared = UUID.randomUUID();
    UUID refA = seedResolvedIdentity(tenant, "erp", "cust-1", shared);
    UUID refB = seedAssertedResolvedIdentity(tenant, "crm", "contact-9", shared, refA);
    seedKey(tenant, "erp", "cust-1", ruleIdentityOf(email),
        fingerprintOf(tenant, email, Map.of("email", "alice@example.com")).orElseThrow());
    seedKey(tenant, "crm", "contact-9", ruleIdentityOf(email),
        fingerprintOf(tenant, email, Map.of("email", "bob@example.com")).orElseThrow());
    markAsserted(tenant, "erp", "cust-1", refA, shared, refB);
    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "retracted");

    resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1", List.of(email), Map.of("email", "alice@example.com"), null);
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);

    assertThat(entityIdOf(tenant, "erp", "cust-1")).isNotEqualTo(shared);
    assertThat(entityIdOf(tenant, "crm", "contact-9")).isNotEqualTo(shared);
    assertThat(entityIdOf(tenant, "erp", "cust-1")).isNotEqualTo(entityIdOf(tenant, "crm", "contact-9"));
  }

  @Test
  void a_distinct_assertion_on_rows_merged_by_a_rule_leaves_exactly_the_first_reprocessed_row_ambiguous() {
    for (boolean firstIsErp : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      ResolutionResult a = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "alice@example.com"), null);
      UUID ref1 = identityRefOf(tenant, "erp", "cust-1");
      UUID ref2 = identityRefOf(tenant, "crm", "contact-9");
      resolver.assertDistinct(tenant, ENTITY_TYPE, ref1, ref2, "reviewer-1", "not the same after all");

      String firstSource = firstIsErp ? "erp" : "crm";
      String firstId = firstIsErp ? "cust-1" : "contact-9";
      String secondSource = firstIsErp ? "crm" : "erp";
      String secondId = firstIsErp ? "contact-9" : "cust-1";
      ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, firstSource, firstId,
          List.of(email), Map.of("email", "alice@example.com"), null);
      ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, secondSource, secondId,
          List.of(email), Map.of("email", "alice@example.com"), null);

      // Today's order-dependent behaviour, pinned so any change is deliberate (#739 leaves it alone).
      assertThat(first.state()).as("first, firstIsErp=%s", firstIsErp).isEqualTo(EntityResolutionState.AMBIGUOUS);
      assertThat(second.state()).as("second, firstIsErp=%s", firstIsErp).isEqualTo(EntityResolutionState.RESOLVED);
      assertThat(second.entityId()).isEqualTo(a.entityId());
    }
  }

  @Test
  void a_distinct_assertion_survives_a_full_replay_the_pair_is_never_remerged() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "shared@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "shared mailbox, different people");

    // Without the assertion, the email rule's raw candidate lookup finds entityA and merges into
    // it. The veto must exclude that candidate at every rule, every pass — contact-9 mints its own.
    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "shared@example.com"), null);
    assertThat(first.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(first.entityId()).isNotNull().isNotEqualTo(entityA);

    // A full replay now finds BOTH entities as raw candidates (contact-9 minted its own last time)
    // — the veto must still exclude entityA, leaving exactly one candidate: itself. Never entityA.
    ResolutionResult replay = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "shared@example.com"), null);
    assertThat(replay.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(replay.entityId()).isEqualTo(first.entityId());
  }

  @Test
  void an_assertion_on_a_pair_of_another_tenant_is_refused() {
    long tenantA = freshTenant();
    long tenantB = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenantA, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenantB, "crm", "contact-1");

    // The three-column FK (identity_ref, tenant_id, entity_type) from #615 makes this structurally
    // impossible, not merely caught by a guard here — pin the SQL cause, not merely "some exception".
    assertThatThrownBy(() ->
        resolver.assertSame(tenantA, ENTITY_TYPE, refA, refB, "reviewer-1", "cross-tenant mistake"))
        .hasMessageContaining("SQLSTATE 23503") // foreign_key_violation; the message never carries the driver's text (#1299)
        .cause().isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_entity_assertion");
  }

  @Test
  void verdict_of_reads_the_current_verdict_for_an_exact_pair_regardless_of_argument_order() {
    long tenant = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person");

    assertThat(repository.<Optional<String>>inTransaction(c -> repository.verdictOf(c, tenant, ENTITY_TYPE, refA, refB)))
        .contains("same");
    // Order must not matter — assertSame/assertDistinct order left/right via LEAST/GREATEST, never
    // the caller's own argument order.
    assertThat(repository.<Optional<String>>inTransaction(c -> repository.verdictOf(c, tenant, ENTITY_TYPE, refB, refA)))
        .contains("same");

    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "correction");
    assertThat(repository.<Optional<String>>inTransaction(c -> repository.verdictOf(c, tenant, ENTITY_TYPE, refA, refB)))
        .contains("distinct");
  }

  @Test
  void verdict_of_is_empty_when_no_assertion_exists_between_the_pair() {
    long tenant = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");

    assertThat(repository.<Optional<String>>inTransaction(c -> repository.verdictOf(c, tenant, ENTITY_TYPE, refA, refB)))
        .isEmpty();
  }

  @Test
  void conflicting_same_assertions_are_recorded_as_ambiguous_not_an_arbitrary_pick() {
    // A is asserted 'same' as BOTH B and C, and B/C are already resolved to two DIFFERENT
    // entities. Picking either arbitrarily would silently name a canonical id no one actually
    // decided on, and could differ between replays. The right answer is to surface this exactly
    // like a cascade-level ambiguity: recorded for review, never silently settled.
    long tenant = freshTenant();
    UUID entityB = UUID.randomUUID();
    UUID entityC = UUID.randomUUID();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-a");
    UUID refB = seedResolvedIdentity(tenant, "crm", "contact-b", entityB);
    UUID refC = seedResolvedIdentity(tenant, "web", "lead-c", entityC);
    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same as B");
    resolver.assertSame(tenant, ENTITY_TYPE, refA, refC, "reviewer-2", "same as C, contradicts reviewer-1");

    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-a",
        List.of(email), Map.of("email", "   "), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    assertThat(decidedByOf(tenant, "erp", "cust-a")).isEqualTo("asserted");
    assertThat(countEvidenceRows(tenant, "erp", "cust-a", null, "ambiguous")).isEqualTo(1);
    assertThat(evidenceDecidedBy(tenant, "erp", "cust-a", "ambiguous")).isEqualTo("asserted");
  }

  @Test
  void conflicting_same_assertions_after_a_prior_resolution_still_record_every_conflicting_partner() {
    // A was previously resolved to priorEntity — so this ambiguity ALSO revokes an existing
    // resolution, which must not crowd out the very reason it happened: the ledger has to show
    // the prior link AND both conflicting assertion partners, or the ambiguity is unreviewable.
    long tenant = freshTenant();
    UUID priorEntity = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    UUID entityC = UUID.randomUUID();
    UUID refA = seedResolvedIdentity(tenant, "erp", "cust-a", priorEntity);
    UUID refB = seedResolvedIdentity(tenant, "crm", "contact-b", entityB);
    UUID refC = seedResolvedIdentity(tenant, "web", "lead-c", entityC);
    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same as B");
    resolver.assertSame(tenant, ENTITY_TYPE, refA, refC, "reviewer-2", "same as C, contradicts reviewer-1");

    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-a",
        List.of(email), Map.of("email", "   "), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(result.entityId()).isNull();
    String candidatesJson = evidenceCandidatesJson(tenant, "erp", "cust-a", "ambiguous");
    assertThat(candidatesJson).contains(priorEntity.toString());
    assertThat(candidatesJson).contains(entityB.toString());
    assertThat(candidatesJson).contains(entityC.toString());
  }

  @Test
  void a_distinct_veto_that_changes_the_outcome_is_recorded_as_asserted_not_automatic() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID entityA = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-1", entityA, email, Map.of("email", "shared@example.com"));
    UUID refA = identityRefOf(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "contact-9");
    resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "shared mailbox, different people");

    // Without the veto this would automatically resolve to entityA; the veto is what makes it
    // mint its own instead — a human assertion produced this outcome, not the cascade on its own.
    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
        List.of(email), Map.of("email", "shared@example.com"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isNotEqualTo(entityA);
    assertThat(decidedByOf(tenant, "crm", "contact-9")).isEqualTo("asserted");
    assertThat(evidenceDecidedBy(tenant, "crm", "contact-9", "resolved")).isEqualTo("asserted");
  }

  @Test
  void a_distinct_veto_that_never_changes_any_rules_own_decision_is_not_recorded_as_asserted() {
    // email's raw candidates are [entityX, entityY, entityZ] — ambiguous either way: vetoing
    // entityX still leaves entityY and entityZ, so the cascade continues regardless of the
    // assertion. civil-identity then accepts entityW entirely on its own, untouched by any veto.
    // The assertion never actually changed what got decided anywhere in this call — recording it
    // as 'asserted' would mislead #622's future observability into thinking a human decision was
    // involved when none was.
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    EntityManifest.CascadeRule civilIdentity =
        exactRule("civil-identity", List.of("last_name", "first_name"), List.of("clean", "lower"));
    List<EntityManifest.CascadeRule> cascade = List.of(email, civilIdentity);

    UUID entityX = UUID.randomUUID();
    UUID entityY = UUID.randomUUID();
    UUID entityZ = UUID.randomUUID();
    UUID entityW = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "x", entityX, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "crm", "y", entityY, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "extra", "z", entityZ, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "web", "w", entityW, civilIdentity, Map.of("last_name", "Martin", "first_name", "Alice"));

    UUID refX = identityRefOf(tenant, "erp", "x");
    UUID refUnderTest = seedUnresolvedIdentity(tenant, "other", "row-1");
    resolver.assertDistinct(tenant, ENTITY_TYPE, refX, refUnderTest, "reviewer-1",
        "unrelated to what actually resolves this row");

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "other", "row-1", cascade,
        Map.of("email", "shared@example.com", "last_name", "Martin", "first_name", "Alice"), null);

    assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(result.entityId()).isEqualTo(entityW);
    assertThat(decidedByOf(tenant, "other", "row-1")).isEqualTo("automatic");
  }

  @Test
  void assert_same_refuses_two_refs_from_the_same_source() {
    // core.entity_identity's own uq_entity_identity_cardinality constraint ("at most one identity
    // per source") makes this merge structurally impossible — without this refusal the assertion
    // above would record fine (204) and only crash later, asynchronously, the next time either row
    // is reprocessed (icreated/lumnik#735). Refused upfront instead, and nothing is persisted.
    long tenant = freshTenant();
    UUID refA = seedResolvedIdentity(tenant, "erp", "cust-1", UUID.randomUUID());
    UUID refB = seedResolvedIdentity(tenant, "erp", "cust-2", UUID.randomUUID());

    assertThatThrownBy(() ->
        resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same source, same person?"))
        .isInstanceOf(SameSourceAssertionException.class)
        .hasMessageContaining("erp");

    assertThat(countRows("SELECT count(*) FROM core.entity_assertion "
        + "WHERE tenant_id = ? AND entity_type = ?", tenant, ENTITY_TYPE)).isZero();
  }

  @Test
  void assert_same_refuses_two_unresolved_refs_from_the_same_source() {
    // source is populated on first upsert regardless of resolution state — the refusal must not
    // depend on either side having reached 'resolved' yet.
    long tenant = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-1");
    UUID refB = seedUnresolvedIdentity(tenant, "erp", "cust-2");

    assertThatThrownBy(() -> resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "typo?"))
        .isInstanceOf(SameSourceAssertionException.class);
  }

  @Test
  void assert_same_allows_two_refs_from_different_sources() {
    // Same shape as the refused test above but for two DIFFERENT sources — the genuine, supported
    // case (also covered end to end by a_same_assertion_links_two_identities_no_rule_matched).
    long tenant = freshTenant();
    UUID refA = seedResolvedIdentity(tenant, "erp", "cust-1", UUID.randomUUID());
    UUID refB = seedResolvedIdentity(tenant, "crm", "contact-1", UUID.randomUUID());

    resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "reviewer-1", "same person, two sources");

    assertThat(countRows("SELECT count(*) FROM core.entity_assertion "
        + "WHERE tenant_id = ? AND entity_type = ?", tenant, ENTITY_TYPE)).isEqualTo(1);
  }

  @Test
  void assert_same_refuses_to_link_an_identity_with_itself() {
    long tenant = freshTenant();
    UUID ref = seedUnresolvedIdentity(tenant, "erp", "cust-1");
    assertThatThrownBy(() -> resolver.assertSame(tenant, ENTITY_TYPE, ref, ref, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void assert_distinct_refuses_to_link_an_identity_with_itself() {
    long tenant = freshTenant();
    UUID ref = seedUnresolvedIdentity(tenant, "erp", "cust-1");
    assertThatThrownBy(() -> resolver.assertDistinct(tenant, ENTITY_TYPE, ref, ref, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void assert_same_refuses_a_blank_entity_type_or_asserted_by() {
    long tenant = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-a");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "cust-b");

    assertThatThrownBy(() -> resolver.assertSame(tenant, null, refA, refB, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertSame(tenant, "  ", refA, refB, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, null, "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertSame(tenant, ENTITY_TYPE, refA, refB, "  ", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void assert_distinct_refuses_a_blank_entity_type_or_asserted_by() {
    long tenant = freshTenant();
    UUID refA = seedUnresolvedIdentity(tenant, "erp", "cust-a");
    UUID refB = seedUnresolvedIdentity(tenant, "crm", "cust-b");

    assertThatThrownBy(() -> resolver.assertDistinct(tenant, null, refA, refB, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertDistinct(tenant, "  ", refA, refB, "reviewer-1", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, null, "typo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refB, "  ", "typo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // One identity per source (#889, design doc §2: "a duplicated ERP row makes a later cross-system
  // match ambiguous, not wrong"). An entity that already holds ANOTHER row of this row's source is not
  // one this row can join — uq_entity_identity_cardinality refuses the upsert — so the decision must
  // never pick it, whichever door it comes through: an exact rule, a provider, or a 'same' assertion.
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_second_row_of_one_source_on_the_same_key_mints_its_own_entity() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));

    ResolutionResult first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "same@example.com"), null);
    ResolutionResult second = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "same@example.com"), null);

    assertThat(second.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(second.entityId()).isNotNull().isNotEqualTo(first.entityId());
    assertThat(second.newlyMinted()).isTrue();
    assertThat(entityIdOf(tenant, "erp", "dup-1")).isEqualTo(first.entityId());
    assertThat(decidedByOf(tenant, "erp", "dup-2")).isEqualTo("automatic");
  }

  @Test
  void replaying_two_rows_of_one_source_keeps_each_on_its_own_entity() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "same@example.com"), null).entityId();
    UUID two = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "same@example.com"), null).entityId();

    // Each replay now finds BOTH entities on the key; the one holding the other row is set aside.
    ResolutionResult replayTwo = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "same@example.com"), null);
    ResolutionResult replayOne = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "same@example.com"), null);

    assertThat(replayTwo.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(replayTwo.entityId()).isEqualTo(two);
    assertThat(replayOne.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(replayOne.entityId()).isEqualTo(one);
  }

  @Test
  void a_later_row_of_another_source_on_that_key_is_ambiguous_between_the_two() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "same@example.com"), null).entityId();
    UUID two = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "same@example.com"), null).entityId();

    ResolutionResult crm = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-1",
        List.of(email), Map.of("email", "same@example.com"), null);

    assertThat(crm.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);
    assertThat(evidenceCandidatesJson(tenant, "crm", "contact-1", "ambiguous"))
        .contains(one.toString()).contains(two.toString());
  }

  @Test
  void a_provider_proposal_of_an_entity_already_holding_this_source_is_set_aside() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "a@example.com"), null).entityId();
    CascadeResolver withProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(one, 0.99)));

    ResolutionResult second = withProvider.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(proposeRule("es", "elasticsearch"), email), Map.of("email", "b@example.com"), null);

    // The strong proposal named the entity erp already holds through dup-1: set aside, the cascade
    // goes on, and dup-2's own key matches nobody — an unseen entity.
    assertThat(second.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(second.entityId()).isNotNull().isNotEqualTo(one);
    assertThat(second.newlyMinted()).isTrue();
  }

  /** The set-aside list of the newest ledger event of this row (#920). */
  private List<EntityIdentityRepository.SetAside> latestSetAside(long tenant, String source, String sourceId) {
    List<EntityIdentityRepository.EvidenceEvent> history = repository.inTransaction(
        c -> repository.evidenceHistory(c, tenant, ENTITY_TYPE, source, sourceId));
    return history.get(history.size() - 1).setAside();
  }

  @Test
  void a_duplicated_source_row_records_the_entity_its_key_rule_set_aside() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID first = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "same@example.com"), null).entityId();
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "same@example.com"), null);

    assertThat(latestSetAside(tenant, "erp", "dup-2"))
        .containsExactly(new EntityIdentityRepository.SetAside(first, "same-source", "email"));
    assertThat(latestSetAside(tenant, "erp", "dup-1")).as("nothing was set aside for the first row").isEmpty();
  }

  @Test
  void a_provider_proposal_set_aside_for_holding_the_source_is_recorded_with_its_rule() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "a@example.com"), null).entityId();
    CascadeResolver withProvider = resolverWith(
        proposing("elasticsearch", new ProposedCandidate(one, 0.99)));

    withProvider.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(proposeRule("es", "elasticsearch"), email), Map.of("email", "b@example.com"), null);

    assertThat(latestSetAside(tenant, "erp", "dup-2"))
        .containsExactly(new EntityIdentityRepository.SetAside(one, "same-source", "es"));
  }

  @Test
  void a_same_assertion_that_does_not_apply_to_the_row_that_cannot_move_leaves_a_trace_on_it() {
    long tenant = freshTenant();
    EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
    UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
        List.of(email), Map.of("email", "alice@example.com"), null).entityId();
    resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-1",
        List.of(email), Map.of("email", "alice@example.com"), null);
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "other@example.com"), null);
    resolver.assertSame(tenant, ENTITY_TYPE, identityRefOf(tenant, "erp", "dup-2"),
        identityRefOf(tenant, "crm", "contact-1"), "reviewer-1", "same person");

    resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
        List.of(email), Map.of("email", "other@example.com"), null);

    // No rule is at fault: the partner's entity holds erp's dup-1, so the assertion cannot move dup-2.
    assertThat(latestSetAside(tenant, "erp", "dup-2"))
        .containsExactly(new EntityIdentityRepository.SetAside(one, "same-source", null));
  }

  @Test
  void a_same_assertion_is_honoured_by_the_side_that_can_move_whichever_is_reprocessed_first() {
    // dup-1 (erp) and contact-1 (crm) on one entity, dup-2 (erp) on its own, then "dup-2 is contact-1".
    // dup-2 cannot move — erp's place in contact-1's entity is dup-1's; reprocessing it used to fail the
    // upsert on the cardinality constraint. contact-1 can: dup-2's entity holds no crm row.
    for (boolean dupTwoFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID one = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-1",
          List.of(email), Map.of("email", "alice@example.com"), null).entityId();
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      UUID two = resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
          List.of(email), Map.of("email", "other@example.com"), null).entityId();
      resolver.assertSame(tenant, ENTITY_TYPE, identityRefOf(tenant, "erp", "dup-2"),
          identityRefOf(tenant, "crm", "contact-1"), "reviewer-1", "same person");

      Runnable dupTwo = () -> resolver.resolve(tenant, ENTITY_TYPE, "erp", "dup-2",
          List.of(email), Map.of("email", "other@example.com"), null);
      Runnable contact = () -> resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      runInOrder(dupTwoFirst, dupTwo, contact);

      assertThat(stateOf(tenant, "erp", "dup-2")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo("resolved");
      assertThat(entityIdOf(tenant, "erp", "dup-2")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo(two);
      assertThat(stateOf(tenant, "crm", "contact-1")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo("resolved");
      assertThat(entityIdOf(tenant, "crm", "contact-1")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo(two);
      assertThat(decidedByOf(tenant, "crm", "contact-1")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo("asserted");
      assertThat(entityIdOf(tenant, "erp", "dup-1")).as("dupTwoFirst=%s", dupTwoFirst).isEqualTo(one);
    }
  }

  @Test
  void the_retraction_check_reads_a_moved_rows_same_partners_the_way_its_own_step_zero_does() {
    // #739 predicts a moved row's own step 0 (heldOnEntityByOtherSame): it must see the partners #889
    // sets aside exactly as that step 0 does. A on E; P (crm) moved onto E by same(A,P); Q moved onto
    // E by same(P,Q); same(P,R) with R on F, which already holds another crm row (P2) — so R cannot take
    // P, and only Q holds P on E. After distinct(A,P), A and P must never both stay RESOLVED on E.
    for (boolean anchorFirst : new boolean[] {true, false}) {
      long tenant = freshTenant();
      EntityManifest.CascadeRule email = exactRule("email", List.of("email"), List.of("clean", "lower"));
      UUID entityE = UUID.randomUUID();
      UUID entityF = UUID.randomUUID();
      seedResolvedRow(tenant, "erp", "cust-1", entityE, email, Map.of("email", "alice@example.com"));
      UUID refA = identityRefOf(tenant, "erp", "cust-1");
      seedResolvedRow(tenant, "crm", "contact-9", UUID.randomUUID(), email, Map.of("email", "bob@example.com"));
      UUID refP = identityRefOf(tenant, "crm", "contact-9");
      seedResolvedRow(tenant, "web", "lead-1", UUID.randomUUID(), email, Map.of("email", "carol@example.com"));
      UUID refQ = identityRefOf(tenant, "web", "lead-1");
      seedResolvedRow(tenant, "ext", "partner-1", entityF, email, Map.of("email", "dave@example.com"));
      UUID refR = identityRefOf(tenant, "ext", "partner-1");
      seedResolvedRow(tenant, "crm", "contact-10", entityF, email, Map.of("email", "erin@example.com"));

      resolver.assertSame(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "same person");
      resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9", List.of(email), Map.of("email", "bob@example.com"), null);
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refQ, "reviewer-1", "same person too");
      resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-1", List.of(email), Map.of("email", "carol@example.com"), null);
      resolver.assertSame(tenant, ENTITY_TYPE, refP, refR, "reviewer-1", "and this one");
      resolver.assertDistinct(tenant, ENTITY_TYPE, refA, refP, "reviewer-1", "retracted");

      ResolutionResult[] out = new ResolutionResult[2];
      Runnable anchor = () -> out[0] = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-1",
          List.of(email), Map.of("email", "alice@example.com"), null);
      Runnable moved = () -> out[1] = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-9",
          List.of(email), Map.of("email", "bob@example.com"), null);
      runInOrder(anchorFirst, anchor, moved);

      boolean bothResolvedTogether = out[0].state() == EntityResolutionState.RESOLVED
          && out[1].state() == EntityResolutionState.RESOLVED
          && out[0].entityId().equals(out[1].entityId());
      assertThat(bothResolvedTogether).as("A and P resolved on one entity, anchorFirst=%s", anchorFirst).isFalse();
    }
  }

  @Test
  void assert_same_refuses_a_pair_neither_of_which_can_join_the_other() {
    long tenant = freshTenant();
    UUID entityOne = UUID.randomUUID();
    UUID entityTwo = UUID.randomUUID();
    seedResolvedIdentity(tenant, "erp", "dup-1", entityOne);
    UUID refContactOne = seedResolvedIdentity(tenant, "crm", "contact-1", entityOne);
    UUID refDupTwo = seedResolvedIdentity(tenant, "erp", "dup-2", entityTwo);
    seedResolvedIdentity(tenant, "crm", "contact-2", entityTwo);

    // dup-2 into contact-1's entity: erp is taken (dup-1). contact-1 into dup-2's: crm is taken
    // (contact-2). No reprocessing order could ever honour it — refused, and nothing is persisted.
    assertThatThrownBy(() -> resolver.assertSame(tenant, ENTITY_TYPE, refDupTwo, refContactOne,
        "reviewer-1", "same person"))
        .isInstanceOf(SameSourceAssertionException.class);
    assertThat(countRows("SELECT count(*) FROM core.entity_assertion "
        + "WHERE tenant_id = ? AND entity_type = ?", tenant, ENTITY_TYPE)).isZero();
  }
}

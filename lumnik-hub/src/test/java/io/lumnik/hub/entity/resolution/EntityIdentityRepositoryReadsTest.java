package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.entity.ProposedCandidate;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #613 lot #622: the read surface the ambiguity API sits on. Exercised against real Postgres —
 * same posture as {@link CascadeResolverTest} — because the query shapes (the lateral "latest
 * evidence per ambiguous identity" join, the stable tie-break order) are exactly what a mocked
 * repository could never prove wrong.
 */
@QuarkusTest
class EntityIdentityRepositoryReadsTest {

  @Inject EntityIdentityRepository repository;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "customer";
  private static final AtomicLong NEXT_TENANT = new AtomicLong(210_000);

  private long freshTenant() {
    return NEXT_TENANT.getAndIncrement();
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

  private UUID seedIdentity(long tenant, String entityType, String source, String sourceId,
      String state, UUID entityId) {
    UUID ref = UUID.randomUUID();
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity "
          + "(tenant_id, entity_type, source, source_id, identity_ref, state, entity_id, decided_by, decided_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, 'automatic', now())")) {
        ps.setLong(1, tenant);
        ps.setString(2, entityType);
        ps.setString(3, source);
        ps.setString(4, sourceId);
        ps.setObject(5, ref);
        ps.setString(6, state);
        ps.setObject(7, entityId);
        ps.executeUpdate();
      }
    });
    return ref;
  }

  /** Seeds one evidence row with an explicit {@code occurredAt} — the stable-order test needs
   *  two rows sharing the exact same instant, which {@code insertEvidence}'s own {@code now()} default
   *  can never produce on demand. */
  private UUID seedEvidence(long tenant, String entityType, String source, String sourceId,
      UUID identityRef, String ruleDeclaredId, String candidatesJson, String discardedJson,
      String outcome, String decidedBy, OffsetDateTime occurredAt) {
    UUID id = UUID.randomUUID();
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_resolution_evidence "
          + "(id, tenant_id, entity_type, source, source_id, identity_ref, rule_declared_id, "
          + "candidates, discarded_candidates, outcome, decided_by, occurred_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)")) {
        ps.setObject(1, id);
        ps.setLong(2, tenant);
        ps.setString(3, entityType);
        ps.setString(4, source);
        ps.setString(5, sourceId);
        ps.setObject(6, identityRef);
        ps.setString(7, ruleDeclaredId);
        ps.setString(8, candidatesJson);
        ps.setString(9, discardedJson);
        ps.setString(10, outcome);
        ps.setString(11, decidedBy);
        ps.setObject(12, Timestamp.from(occurredAt.toInstant()));
        ps.executeUpdate();
      }
    });
    return id;
  }

  // ---------------------------------------------------------------------------------------------
  // listAmbiguous
  // ---------------------------------------------------------------------------------------------

  @Test
  void listAmbiguous_returns_the_ambiguous_row_with_its_latest_evidence() {
    long tenant = freshTenant();
    UUID candidateA = UUID.randomUUID();
    UUID candidateB = UUID.randomUUID();
    UUID identityRef = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", identityRef, "email",
        "[\"" + candidateA + "\",\"" + candidateB + "\"]", "[]", "ambiguous", "automatic",
        OffsetDateTime.now());

    List<EntityIdentityRepository.AmbiguousIdentity> rows = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 100, 0));

    assertThat(rows).hasSize(1);
    EntityIdentityRepository.AmbiguousIdentity row = rows.get(0);
    assertThat(row.identityRef()).isEqualTo(identityRef);
    assertThat(row.source()).isEqualTo("erp");
    assertThat(row.sourceId()).isEqualTo("cust-1");
    assertThat(row.candidates()).containsExactlyInAnyOrder(candidateA, candidateB);
    assertThat(row.discardedCandidates()).isEmpty();
    assertThat(row.decidedBy()).isEqualTo("automatic");
  }

  @Test
  void listAmbiguous_carries_the_discarded_candidates_of_the_latest_evidence_row() {
    long tenant = freshTenant();
    UUID ghost = UUID.randomUUID();
    UUID identityRef = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-2", "ambiguous", null);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-2", identityRef, "propose:search",
        "[]", "[{\"entityId\":\"" + ghost + "\",\"score\":0.4}]", "ambiguous", "automatic",
        OffsetDateTime.now());

    List<EntityIdentityRepository.AmbiguousIdentity> rows = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 100, 0));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).discardedCandidates())
        .containsExactly(new ProposedCandidate(ghost, 0.4));
  }

  @Test
  void the_set_aside_of_an_event_comes_back_from_the_ambiguity_list_and_from_the_history() {
    long tenant = freshTenant();
    UUID holder = UUID.randomUUID();
    UUID identityRef = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-3", "ambiguous", null);
    UUID evidenceId = seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-3", identityRef, "email",
        "[]", "[]", "ambiguous", "automatic", OffsetDateTime.now());
    exec(c -> {
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE core.entity_resolution_evidence SET set_aside = ?::jsonb WHERE id = ?")) {
        ps.setString(1, "[{\"entityId\":\"" + holder + "\",\"reason\":\"same-source\",\"rule\":\"email\"}]");
        ps.setObject(2, evidenceId);
        ps.executeUpdate();
      }
    });
    EntityIdentityRepository.SetAside expected = new EntityIdentityRepository.SetAside(holder, "same-source", "email");

    assertThat(repository.inTransaction(c -> repository.listAmbiguous(c, tenant, ENTITY_TYPE, 100, 0)).get(0)
        .setAside()).containsExactly(expected);
    assertThat(repository.inTransaction(c -> repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-3")).get(0)
        .setAside()).containsExactly(expected);
  }

  @Test
  void listAmbiguous_excludes_resolved_and_unresolved_rows() {
    long tenant = freshTenant();
    seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-resolved", "resolved", UUID.randomUUID());
    seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-unresolved", "unresolved_no_rule", null);

    List<EntityIdentityRepository.AmbiguousIdentity> rows = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 100, 0));

    assertThat(rows).isEmpty();
  }

  @Test
  void listAmbiguous_is_scoped_to_tenant_and_entity_type() {
    long tenant = freshTenant();
    long otherTenant = freshTenant();
    seedIdentity(otherTenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    seedIdentity(tenant, "supplier", "erp", "sup-1", "ambiguous", null);

    List<EntityIdentityRepository.AmbiguousIdentity> rows = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 100, 0));

    assertThat(rows).isEmpty();
  }

  @Test
  void listAmbiguous_paginates_with_limit_and_offset() {
    long tenant = freshTenant();
    for (int i = 0; i < 3; i++) {
      UUID ref = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-" + i, "ambiguous", null);
      seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-" + i, ref, "email", "[]", "[]",
          "ambiguous", "automatic", OffsetDateTime.now());
    }

    List<EntityIdentityRepository.AmbiguousIdentity> page1 = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 2, 0));
    List<EntityIdentityRepository.AmbiguousIdentity> page2 = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenant, ENTITY_TYPE, 2, 2));

    assertThat(page1).hasSize(2);
    assertThat(page2).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // evidenceHistory
  // ---------------------------------------------------------------------------------------------

  @Test
  void evidenceHistory_returns_every_event_oldest_first() {
    long tenant = freshTenant();
    UUID ref = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(10);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", ref, "email", "[]", "[]",
        "unresolved_no_rule", "automatic", t0);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", ref, "email", "[]", "[]",
        "ambiguous", "automatic", t0.plusMinutes(5));

    List<EntityIdentityRepository.EvidenceEvent> events = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1"));

    assertThat(events).hasSize(2);
    assertThat(events.get(0).outcome()).isEqualTo(EntityResolutionState.UNRESOLVED_NO_RULE);
    assertThat(events.get(1).outcome()).isEqualTo(EntityResolutionState.AMBIGUOUS);
  }

  @Test
  void evidenceHistory_breaks_a_tied_occurred_at_by_id_for_a_stable_order() {
    long tenant = freshTenant();
    UUID ref = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    OffsetDateTime sameInstant = OffsetDateTime.now();
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", ref, "email", "[]", "[]",
        "unresolved_no_rule", "automatic", sameInstant);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", ref, "phone", "[]", "[]",
        "ambiguous", "automatic", sameInstant);

    // Not which order wins — id is a random UUID, and Postgres orders uuid byte-for-byte while
    // Java's UUID.compareTo orders by two signed 64-bit halves, so predicting the winner from
    // Java would itself be wrong on some fraction of runs (the same divergence upsertAssertion's
    // own LEAST/GREATEST comment describes). What must hold is that ONE order wins consistently,
    // never "whichever the planner feels like this time".
    List<EntityIdentityRepository.EvidenceEvent> events = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1"));
    List<EntityIdentityRepository.EvidenceEvent> replay = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1"));

    assertThat(events).extracting(EntityIdentityRepository.EvidenceEvent::ruleDeclaredId)
        .containsExactlyInAnyOrder("email", "phone");
    assertThat(events).extracting(EntityIdentityRepository.EvidenceEvent::ruleDeclaredId)
        .as("the order must be stable across replays, not merely consistent within one call")
        .isEqualTo(replay.stream().map(EntityIdentityRepository.EvidenceEvent::ruleDeclaredId).toList());
  }

  @Test
  void evidenceHistory_is_scoped_to_the_exact_source_identity() {
    long tenant = freshTenant();
    UUID refA = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    UUID refB = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-2", "ambiguous", null);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", refA, "email", "[]", "[]",
        "ambiguous", "automatic", OffsetDateTime.now());
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-2", refB, "email", "[]", "[]",
        "ambiguous", "automatic", OffsetDateTime.now());

    List<EntityIdentityRepository.EvidenceEvent> events = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1"));

    assertThat(events).hasSize(1);
  }

  @Test
  void evidenceHistory_is_empty_for_an_identity_with_no_ledger_rows() {
    long tenant = freshTenant();

    List<EntityIdentityRepository.EvidenceEvent> events = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "never-seen"));

    assertThat(events).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Malformed evidence JSON (Copilot review on PR #645): valid JSON that does not bind to
  // List<UUID>/List<ProposedCandidate> must never echo the raw payload into the thrown exception
  // — the ledger could grow large or later carry sensitive fields, and an exception message can
  // reach a log an operator shares, or (depending on the exception mapper) a response body.
  // ---------------------------------------------------------------------------------------------

  // A distinctive VALUE, not the full object literal: Postgres' own jsonb text serialization
  // reformats whitespace around syntax delimiters (e.g. adds a space after ':'), so comparing
  // against the exact Java string literal we inserted would drift from what a real round trip
  // through the jsonb column actually returns — this marker survives that untouched, since
  // Postgres never rewrites a quoted string's own content.
  private static final String MALFORMED_MARKER = "shape-not-a-uuid-list";
  private static final String MALFORMED_PAYLOAD = "{\"unexpected\":\"" + MALFORMED_MARKER + "\"}";

  @Test
  void malformed_candidates_json_never_echoes_the_payload_in_the_exception_message() {
    long tenant = freshTenant();
    UUID ref = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-1", "ambiguous", null);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-1", ref, "email", MALFORMED_PAYLOAD, "[]",
        "ambiguous", "automatic", OffsetDateTime.now());

    assertThatThrownBy(() -> repository.inTransaction(c ->
        repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-1")))
        .isInstanceOf(RuntimeException.class)
        .satisfies(e -> assertThat(rootMessageOf(e)).doesNotContain(MALFORMED_MARKER));
  }

  @Test
  void malformed_candidates_json_is_still_logged_for_an_operator_to_diagnose() {
    long tenant = freshTenant();
    UUID ref = seedIdentity(tenant, ENTITY_TYPE, "erp", "cust-2", "ambiguous", null);
    seedEvidence(tenant, ENTITY_TYPE, "erp", "cust-2", ref, "email", MALFORMED_PAYLOAD, "[]",
        "ambiguous", "automatic", OffsetDateTime.now());

    List<LogProbe.Line> lines = LogProbe.capture(EntityIdentityRepository.class, () -> {
      try {
        repository.inTransaction(c -> repository.evidenceHistory(c, tenant, ENTITY_TYPE, "erp", "cust-2"));
      } catch (RuntimeException ignored) {
        // the throw is this test's OTHER assertion's job — here only the log line matters.
      }
    });

    assertThat(lines).anySatisfy(l -> assertThat(l.message()).contains(MALFORMED_MARKER));
  }

  private static String rootMessageOf(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null) root = root.getCause();
    return t.getMessage() + " | " + root.getMessage();
  }
}

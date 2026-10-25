package io.lumnik.hub.entity.resolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.entity.ProposedCandidate;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Raw-SQL access to the V49 crosswalk (design doc §3), on the {@code admin} datasource with the
 * tenant bound explicitly in every statement — the same posture as {@link
 * io.lumnik.hub.process.ProcessManifestStore}: RLS on {@code default} exists for the app-facing
 * path, this internal engine enforces the tenant boundary itself. Multi-statement work
 * ({@link CascadeResolver}'s one decision per row) shares a single transaction via
 * {@link #inTransaction}, whose boundary stays visible at the call site; each statement is a
 * {@code Jdbc} call on that transaction's connection.
 */
@ApplicationScoped
public class EntityIdentityRepository {

  private static final Logger LOG = Logger.getLogger(EntityIdentityRepository.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {};
  private static final TypeReference<List<ProposedCandidate>> PROPOSED_CANDIDATE_LIST = new TypeReference<>() {};
  private static final TypeReference<List<SetAside>> SET_ASIDE_LIST = new TypeReference<>() {};

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  public record ExistingIdentity(UUID identityRef, String state, UUID entityId) {}

  /** One ambiguous crosswalk row (#622), enriched with the candidates/discarded/decidedBy of its
   *  most recent ledger event — {@link #listAmbiguous} is the query that produces it. */
  public record AmbiguousIdentity(UUID identityRef, String source, String sourceId,
      List<UUID> candidates, List<ProposedCandidate> discardedCandidates, List<SetAside> setAside,
      String decidedBy, OffsetDateTime occurredAt) {}

  /** An entity the resolver set aside because it already holds another row of the row's source (#920) —
   *  {@code rule} is the cascade rule that found it, null for a partner of a {@code same} assertion. */
  public record SetAside(UUID entityId, String reason, String rule) {
    public static final String SAME_SOURCE = "same-source";

    public static SetAside sameSource(UUID entityId, String rule) {
      return new SetAside(entityId, SAME_SOURCE, rule);
    }
  }

  /** One append-only ledger row, typed for a caller rather than the raw JSON/text {@link
   *  #insertEvidence} writes — {@link #evidenceHistory} is the query that produces it (#622).
   *  {@code assertedBy}/{@code reason} name the human correction behind an asserted decision
   *  (#1343), null on every other event — copied onto the row when the event was written (#1520),
   *  so a later correction of the same pair never rewrites them. {@code assertedBy} is the stored
   *  user id; {@code assertedByLogin} is that user's login read now (#1347), null when the id no
   *  longer names a user of the tenant — never stored, so renaming a user rewrites no audit line. */
  public record EvidenceEvent(String ruleDeclaredId, String ruleVersion, String provider,
      List<UUID> candidates, List<ProposedCandidate> discardedCandidates, List<SetAside> setAside,
      EntityResolutionState outcome, String decidedBy, UUID runId, OffsetDateTime occurredAt,
      String assertedBy, String assertedByLogin, String reason) {}

  /** Runs {@code work} in one transaction; committed on return, rolled back and rethrown on failure. */
  public <T> T inTransaction(Function<Connection, T> work) {
    return Jdbc.transaction(adminDs, "EntityIdentityRepository.inTransaction", work::apply);
  }

  public Optional<ExistingIdentity> find(
      Connection c, long tenantId, String entityType, String source, String sourceId) {
    String sql = "SELECT identity_ref, state, entity_id FROM core.entity_identity "
        + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?";
    return Jdbc.queryOne(c, "EntityIdentityRepository.find", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
    }, rs -> new ExistingIdentity(
        (UUID) rs.getObject(1), rs.getString(2), (UUID) rs.getObject(3)));
  }

  /**
   * What this source row was most recently, genuinely resolved to, from the append-only ledger —
   * not the live crosswalk row — plus how that decision was made and, when it was a {@code same}
   * assertion, which single partner ref produced it (icreated/lumnik#737). A refused swap (design
   * doc §10) sets the live row to {@code ambiguous}, which would otherwise erase the memory of what
   * it used to be; the ledger is what keeps that refusal effective across every later replay, not
   * just the one call that made it.
   */
  public record LastResolvedEvidence(UUID entityId, String decidedBy, UUID assertedPartnerRef,
      String ruleDeclaredId, String ruleVersion) {}

  private static LastResolvedEvidence evidenceRow(ResultSet rs) throws SQLException {
    String idText = rs.getString(1);
    if (idText == null) return null;
    return new LastResolvedEvidence(
        UUID.fromString(idText), rs.getString(2), (UUID) rs.getObject(3), rs.getString(4), rs.getString(5));
  }

  public Optional<LastResolvedEvidence> lastResolvedEvidence(
      Connection c, long tenantId, String entityType, String source, String sourceId) {
    String sql = "SELECT candidates ->> 0, decided_by, asserted_partner_ref, rule_declared_id, rule_version "
        + "FROM core.entity_resolution_evidence "
        + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ? "
        + "AND outcome = 'resolved' ORDER BY occurred_at DESC, id DESC LIMIT 1";
    return Jdbc.queryOne(c, "EntityIdentityRepository.lastResolvedEvidence", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
    }, EntityIdentityRepository::evidenceRow);
  }

  /**
   * {@link #lastResolvedEvidence} addressed by {@code identity_ref} instead of {@code (source,
   * source_id)} — an assertion names its partner only by ref (icreated/lumnik#739).
   */
  public Optional<LastResolvedEvidence> lastResolvedEvidenceOfRef(
      Connection c, long tenantId, String entityType, UUID identityRef) {
    String sql = "SELECT candidates ->> 0, decided_by, asserted_partner_ref, rule_declared_id, rule_version "
        + "FROM core.entity_resolution_evidence "
        + "WHERE tenant_id = ? AND entity_type = ? AND (source, source_id) = "
        + "(SELECT source, source_id FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ? "
        + "AND identity_ref = ?) "
        + "AND outcome = 'resolved' ORDER BY occurred_at DESC, id DESC LIMIT 1";
    return Jdbc.queryOne(c, "EntityIdentityRepository.lastResolvedEvidenceOfRef", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setLong(3, tenantId);
      ps.setString(4, entityType);
      ps.setObject(5, identityRef);
    }, EntityIdentityRepository::evidenceRow);
  }

  /**
   * The entities a key match offers {@code (source, sourceId)}: {@code joinable} ones, and the {@code
   * setAside} ones that already hold another row of its source (#889, #920).
   */
  public record KeyMatches(List<UUID> joinable, List<UUID> setAside) {}

  /**
   * Distinct candidate entities already resolved under this exact rule id, version and
   * fingerprint. DISTINCT on {@code entity_id} (not on the matching source rows) is what keeps
   * several source rows already merged into one entity from reading as ambiguous.
   *
   * <p>Never joinable: an entity that already holds another row of {@code source} (#889) — one identity
   * per source makes it unable to take the row {@code (source, sourceId)}, so a duplicated source row
   * finds nobody it can join and mints its own entity, and a later row of another source on the same
   * key then sees both, and goes ambiguous (design doc §2). It comes back as set aside instead, for
   * the evidence ledger (#920). The row's own entity stays joinable; the predicate is served by {@code
   * uq_entity_identity_cardinality}.
   */
  public KeyMatches candidateEntityIds(Connection c, long tenantId, String entityType, String source,
      String sourceId, String ruleDeclaredId, String ruleVersion, byte[] fingerprint) {
    String sql = "SELECT DISTINCT ei.entity_id, EXISTS (SELECT 1 FROM core.entity_identity h "
        + "  WHERE h.tenant_id = ei.tenant_id AND h.entity_type = ei.entity_type AND h.source = ? "
        + "    AND h.source_id <> ? AND h.entity_id = ei.entity_id) "
        + "FROM core.entity_identity_key eik "
        + "JOIN core.entity_identity ei "
        + "  ON ei.tenant_id = eik.tenant_id AND ei.entity_type = eik.entity_type "
        + "  AND ei.source = eik.source AND ei.source_id = eik.source_id "
        + "WHERE eik.tenant_id = ? AND eik.entity_type = ? AND eik.rule_declared_id = ? "
        + "  AND eik.rule_version = ? AND eik.key_fingerprint = ? "
        + "  AND ei.state = 'resolved' AND ei.entity_id IS NOT NULL";
    List<KeyMatchRow> rows = Jdbc.query(c, "EntityIdentityRepository.candidateEntityIds", sql, ps -> {
      ps.setString(1, source);
      ps.setString(2, sourceId);
      ps.setLong(3, tenantId);
      ps.setString(4, entityType);
      ps.setString(5, ruleDeclaredId);
      ps.setString(6, ruleVersion);
      ps.setBytes(7, fingerprint);
    }, rs -> new KeyMatchRow((UUID) rs.getObject(1), rs.getBoolean(2)));
    List<UUID> joinable = new ArrayList<>();
    List<UUID> setAside = new ArrayList<>();
    for (KeyMatchRow row : rows) (row.setAside() ? setAside : joinable).add(row.entityId());
    return new KeyMatches(joinable, setAside);
  }

  private record KeyMatchRow(UUID entityId, boolean setAside) {}

  /**
   * Upserts the crosswalk row. {@code identity_ref} is deliberately absent from the {@code DO
   * UPDATE SET} list — the immutable surrogate an assertion addresses must survive every later
   * re-decision. Returns it either way. {@code decidedBy} is {@code 'automatic'} for every ordinary
   * cascade decision and {@code 'asserted'} only when a human assertion (#619) is what produced
   * this outcome — one value per call, since one {@code resolve()} call makes exactly one decision.
   */
  public UUID upsertIdentity(Connection c, long tenantId, String entityType, String source,
      String sourceId, EntityResolutionState state, UUID entityId, String decidedBy) {
    String sql = "INSERT INTO core.entity_identity "
        + "(tenant_id, entity_type, source, source_id, state, entity_id, decided_by, decided_at) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) "
        + "ON CONFLICT (tenant_id, entity_type, source, source_id) DO UPDATE SET "
        + "state = EXCLUDED.state, entity_id = EXCLUDED.entity_id, decided_by = EXCLUDED.decided_by, decided_at = now() "
        + "RETURNING identity_ref";
    return Jdbc.queryOne(c, "EntityIdentityRepository.upsertIdentity", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setString(5, state.sqlValue());
      ps.setObject(6, entityId, Types.OTHER);
      ps.setString(7, decidedBy);
    }, rs -> (UUID) rs.getObject(1)).orElseThrow();
  }

  /** Where an existing {@code identity_ref} sits: its source row and, when resolved, its entity. */
  public record Seat(String source, String sourceId, UUID entityId) {}

  /**
   * The {@link Seat} of an existing {@code identity_ref} within this tenant/entityType, or empty when
   * no such row exists yet — used only by {@link CascadeResolver#assertSame}'s refusals (#735, #889).
   * An absent ref is left to the ordinary {@code fk_entity_assertion*} constraint translation
   * downstream, not this lookup.
   */
  public Optional<Seat> seatOf(Connection c, long tenantId, String entityType, UUID identityRef) {
    String sql = "SELECT source, source_id, entity_id FROM core.entity_identity "
        + "WHERE tenant_id = ? AND entity_type = ? AND identity_ref = ?";
    return Jdbc.queryOne(c, "EntityIdentityRepository.seatOf", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setObject(3, identityRef);
    }, rs -> new Seat(rs.getString(1), rs.getString(2), (UUID) rs.getObject(3)));
  }

  /**
   * One assertion partner reachable via a {@code same}/{@code distinct} verdict from a given ref,
   * paired with that partner's own currently-resolved entity id — {@link CascadeResolver} needs
   * both: the entity id to decide the natural outcome, the partner ref to later prove (via {@link
   * #verdictOf}) whether the specific assertion that produced a merge has since been retracted
   * (icreated/lumnik#737).
   */
  public record AssertedPartner(UUID partnerRef, UUID entityId) {}

  /**
   * A currently-resolved partner linked to {@code myRef} by a {@code same} assertion, if any —
   * design doc §7 step 0, consulted before any cascade rule and outranking every one of them.
   * Distinct on {@code (identity_ref, entity_id)}, same discipline as {@link #candidateEntityIds} —
   * never a bare row count. Empty when no such assertion exists yet, or when no partner has itself
   * resolved to anything (a {@code same} assertion between two still-unresolved rows has nothing
   * concrete to hand back yet; it takes effect once whichever side resolves first is replayed).
   * More than one distinct entity id among the returned partners means two {@code same} assertions
   * on this identity disagree with each other — the caller must not pick one arbitrarily; see
   * {@link CascadeResolver}, which surfaces that as {@code ambiguous} for review rather than
   * silently naming a canonical id no one actually decided.
   *
   * <p>A partner whose entity already holds ANOTHER row of {@code myRef}'s source is left out (#889):
   * one identity per source makes that entity unable to take {@code myRef}'s row, so the assertion
   * does not apply to it — the partner is the side that moves. Filtered here, in the one query both
   * readers use, so step 0 and #739's prediction of a partner's step 0 ({@code
   * heldOnEntityByOtherSame}) can never read two different lists.
   */
  public List<AssertedPartner> assertedSameEntityIds(Connection c, long tenantId, String entityType, UUID myRef) {
    return assertedSamePartners(c, tenantId, entityType, myRef).applicable();
  }

  /**
   * The {@code same} partners of {@code myRef}, split (#920): the {@code applicable} ones {@link
   * #assertedSameEntityIds} returns, and the {@code setAside} ones whose entity holds another row of
   * {@code myRef}'s source — kept so the evidence ledger can say the assertion did not apply.
   */
  public record SamePartners(List<AssertedPartner> applicable, List<AssertedPartner> setAside) {}

  public SamePartners assertedSamePartners(Connection c, long tenantId, String entityType, UUID myRef) {
    List<AssertedPartner> applicable = new ArrayList<>();
    List<AssertedPartner> setAside = new ArrayList<>();
    for (PartnerRow row : assertedPartnerRows(c, tenantId, entityType, myRef, "same")) {
      (row.setAside() ? setAside : applicable).add(row.partner());
    }
    return new SamePartners(applicable, setAside);
  }

  /**
   * The currently-resolved {@code distinct} partners of {@code myRef} with their refs kept —
   * {@link CascadeResolver} needs the ref to tell a partner that was merely vetoed from the one that
   * was MOVED onto this row's entity by a since-retracted {@code same} assertion
   * (icreated/lumnik#739).
   */
  public List<AssertedPartner> assertedDistinctPartners(Connection c, long tenantId, String entityType, UUID myRef) {
    return assertedPartnerRows(c, tenantId, entityType, myRef, "distinct").stream()
        .map(PartnerRow::partner).toList();
  }

  private record PartnerRow(AssertedPartner partner, boolean setAside) {}

  private List<PartnerRow> assertedPartnerRows(
      Connection c, long tenantId, String entityType, UUID myRef, String verdict) {
    // #889, same only: a partner whose entity already holds another row of myRef's source cannot take it
    // (see assertedSameEntityIds); flagged here, not filtered, so #920 can record it.
    String setAside = "same".equals(verdict)
        ? "EXISTS (SELECT 1 FROM core.entity_identity me JOIN core.entity_identity h "
            + "ON h.tenant_id = me.tenant_id AND h.entity_type = me.entity_type AND h.source = me.source "
            + "AND h.source_id <> me.source_id "
            + "WHERE me.tenant_id = a.tenant_id AND me.entity_type = a.entity_type AND me.identity_ref = ? "
            + "AND h.entity_id = ei.entity_id)"
        : "false";
    String sql = "SELECT DISTINCT ei.identity_ref, ei.entity_id, " + setAside + " FROM core.entity_assertion a "
        + "JOIN core.entity_identity ei ON ei.tenant_id = a.tenant_id AND ei.entity_type = a.entity_type "
        + "  AND ei.identity_ref = CASE WHEN a.left_identity_ref = ? THEN a.right_identity_ref ELSE a.left_identity_ref END "
        + "WHERE a.tenant_id = ? AND a.entity_type = ? AND (a.left_identity_ref = ? OR a.right_identity_ref = ?) "
        + "  AND a.verdict = ? AND ei.state = 'resolved' AND ei.entity_id IS NOT NULL";
    return Jdbc.query(c, "EntityIdentityRepository.assertedPartnerRows", sql, ps -> {
      int i = 1;
      if ("same".equals(verdict)) ps.setObject(i++, myRef);
      ps.setObject(i++, myRef);
      ps.setLong(i++, tenantId);
      ps.setString(i++, entityType);
      ps.setObject(i++, myRef);
      ps.setObject(i++, myRef);
      ps.setString(i, verdict);
    }, rs -> new PartnerRow(new AssertedPartner((UUID) rs.getObject(1), (UUID) rs.getObject(2)), rs.getBoolean(3)));
  }

  /**
   * Records a human correction on a pair of identities (#619, design doc §7). Left/right are
   * ordered by Postgres' own {@code LEAST}/{@code GREATEST} — never compared in Java — because
   * {@code UUID.compareTo} orders by two SIGNED 64-bit halves while Postgres compares {@code uuid}
   * byte-for-byte; the two can disagree, and a Java-side ordering decision could then violate the
   * very {@code ck_entity_assertion_order} CHECK it exists to satisfy. Re-asserting an already
   * asserted pair updates its verdict — a human is allowed to correct their own prior call.
   */
  private void upsertAssertion(Connection c, long tenantId, String entityType, UUID refA, UUID refB,
      String verdict, String assertedBy, String reason) {
    String sql = "INSERT INTO core.entity_assertion "
        + "(tenant_id, entity_type, left_identity_ref, right_identity_ref, verdict, asserted_by, reason) "
        + "VALUES (?, ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid), ?, ?, ?) "
        + "ON CONFLICT (tenant_id, entity_type, left_identity_ref, right_identity_ref) DO UPDATE SET "
        + "verdict = EXCLUDED.verdict, asserted_by = EXCLUDED.asserted_by, reason = EXCLUDED.reason, asserted_at = now()";
    Jdbc.update(c, "EntityIdentityRepository.upsertAssertion", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setObject(3, refA);
      ps.setObject(4, refB);
      ps.setObject(5, refA);
      ps.setObject(6, refB);
      ps.setString(7, verdict);
      ps.setString(8, assertedBy);
      ps.setString(9, reason);
    });
  }

  /**
   * The current verdict ({@code same}/{@code distinct}) recorded between exactly this pair of
   * identity refs, or empty when no assertion exists between them — the local, order-independent
   * proof {@link CascadeResolver}'s un-merge exemption needs (icreated/lumnik#737): it depends only
   * on the assertion table's own current state for this specific pair, never on either ref's live
   * resolution status, which is what keeps the check stable regardless of which of two
   * mutually-asserted rows happens to be reprocessed first.
   */
  public Optional<String> verdictOf(Connection c, long tenantId, String entityType, UUID refA, UUID refB) {
    return assertionOf(c, tenantId, entityType, refA, refB).map(Assertion::verdict);
  }

  /** The assertion recorded between exactly this pair, as it stands now: its verdict, who made it and why. */
  public record Assertion(String verdict, String assertedBy, String reason) {}

  public Optional<Assertion> assertionOf(Connection c, long tenantId, String entityType, UUID refA, UUID refB) {
    String sql = "SELECT verdict, asserted_by, reason FROM core.entity_assertion WHERE tenant_id = ? AND entity_type = ? "
        + "AND left_identity_ref = LEAST(?::uuid, ?::uuid) AND right_identity_ref = GREATEST(?::uuid, ?::uuid)";
    return Jdbc.queryOne(c, "EntityIdentityRepository.assertionOf", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setObject(3, refA);
      ps.setObject(4, refB);
      ps.setObject(5, refA);
      ps.setObject(6, refB);
    }, rs -> new Assertion(rs.getString(1), rs.getString(2), rs.getString(3)));
  }

  /** "These two source identities are the same" — the positive assertion (#619). */
  public void assertSame(Connection c, long tenantId, String entityType, UUID refA, UUID refB,
      String assertedBy, String reason) {
    upsertAssertion(c, tenantId, entityType, refA, refB, "same", assertedBy, reason);
  }

  /**
   * "These two are not" — the half that carries the weight (#619): expressed about a pair that is
   * not linked yet, which is exactly why assertions address {@code identity_ref} and not {@code
   * entity_id}.
   */
  public void assertDistinct(Connection c, long tenantId, String entityType, UUID refA, UUID refB,
      String assertedBy, String reason) {
    upsertAssertion(c, tenantId, entityType, refA, refB, "distinct", assertedBy, reason);
  }

  /**
   * Removes one rule's materialized fingerprint — a field this rule reads is no longer usable
   * (absent or blank) on re-evaluation, so its old key must not keep matching future rows against
   * data this identity no longer carries.
   */
  public void deleteKey(Connection c, long tenantId, String entityType, String source, String sourceId,
      String ruleDeclaredId) {
    String sql = "DELETE FROM core.entity_identity_key WHERE tenant_id = ? AND entity_type = ? "
        + "AND source = ? AND source_id = ? AND rule_declared_id = ?";
    Jdbc.update(c, "EntityIdentityRepository.deleteKey", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setString(5, ruleDeclaredId);
    });
  }

  /** Upserts one rule's materialized fingerprint — one row per (identity, declared rule). */
  public void upsertKey(Connection c, long tenantId, String entityType, String source, String sourceId,
      String ruleDeclaredId, String ruleVersion, byte[] fingerprint) {
    String sql = "INSERT INTO core.entity_identity_key "
        + "(tenant_id, entity_type, source, source_id, rule_declared_id, rule_version, key_fingerprint) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
        + "ON CONFLICT (tenant_id, entity_type, source, source_id, rule_declared_id) DO UPDATE SET "
        + "rule_version = EXCLUDED.rule_version, key_fingerprint = EXCLUDED.key_fingerprint, updated_at = now()";
    Jdbc.update(c, "EntityIdentityRepository.upsertKey", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setString(5, ruleDeclaredId);
      ps.setString(6, ruleVersion);
      ps.setBytes(7, fingerprint);
    });
  }

  /**
   * Appends one row to the append-only ledger — never UPDATE/DELETE, a new event instead.
   * {@code decidedBy} is {@code 'automatic'} for an ordinary cascade decision and {@code
   * 'asserted'} when a human assertion (#619) produced it — the CHECK constraint on this column
   * restricts it to exactly those two values. {@code assertedPartnerRef} is non-null only for the
   * one event a {@code sameAssertionResolved} decision produces (icreated/lumnik#737); every other
   * event passes {@code null}. {@code assertion} is the correction an asserted decision acted on, its
   * {@code assertedBy}/{@code reason} stored on the row as they stand now (#1520); null when no single
   * assertion is behind the decision.
   */
  public void insertEvidence(Connection c, long tenantId, String entityType, String source, String sourceId,
      UUID identityRef, String ruleDeclaredId, String ruleVersion, String provider,
      String candidatesJson, String discardedCandidatesJson, String setAsideJson, EntityResolutionState outcome,
      String decidedBy, UUID runId, UUID assertedPartnerRef, Assertion assertion) {
    String sql = "INSERT INTO core.entity_resolution_evidence "
        + "(tenant_id, entity_type, source, source_id, identity_ref, rule_declared_id, rule_version, "
        + "provider, candidates, discarded_candidates, set_aside, outcome, decided_by, run_id, "
        + "asserted_partner_ref, asserted_by, assertion_reason) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)";
    Jdbc.update(c, "EntityIdentityRepository.insertEvidence", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setObject(5, identityRef);
      ps.setString(6, ruleDeclaredId);
      ps.setString(7, ruleVersion);
      ps.setString(8, provider);
      ps.setString(9, candidatesJson);
      ps.setString(10, discardedCandidatesJson);
      ps.setString(11, setAsideJson);
      ps.setString(12, outcome.sqlValue());
      ps.setString(13, decidedBy);
      ps.setObject(14, runId, Types.OTHER);
      ps.setObject(15, assertedPartnerRef, Types.OTHER);
      ps.setString(16, assertion == null ? null : assertion.assertedBy());
      ps.setString(17, assertion == null ? null : assertion.reason());
    });
  }

  /**
   * Which of {@code candidateIds} are real, resolved entities in this tenant's crosswalk — the
   * check a {@code CandidateProvider}'s proposals must pass before any of them can be scored
   * (#621): a stale search index naming an id the crosswalk has never minted must never manufacture
   * a ghost entity. Batched in one query rather than one per candidate — same posture as {@link
   * #candidateEntityIds} — since a provider can plausibly propose a double-digit candidate list. A
   * null id in {@code candidateIds} (a malformed proposal) simply never matches; {@code = ANY(...)}
   * against an array containing {@code NULL} is well-defined SQL, nothing to special-case.
   */
  public Set<UUID> existingEntityIds(Connection c, long tenantId, String entityType, List<UUID> candidateIds) {
    if (candidateIds.isEmpty()) return Set.of();
    String sql = "SELECT DISTINCT entity_id FROM core.entity_identity "
        + "WHERE tenant_id = ? AND entity_type = ? AND entity_id = ANY(?) AND state = 'resolved'";
    return new HashSet<>(Jdbc.query(c, "EntityIdentityRepository.existingEntityIds", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setArray(3, c.createArrayOf("uuid", candidateIds.toArray()));
    }, rs -> (UUID) rs.getObject(1)));
  }

  /**
   * Which of {@code entityIds} already hold an identity of {@code source} under a source id other
   * than {@code sourceId} — entities the row {@code (source, sourceId)} cannot join, because {@code
   * uq_entity_identity_cardinality} allows one identity per source (#889); picking one failed the
   * upsert and aborted the whole resolution pass. That same unique index serves the lookup. The row's
   * own identity never counts against it, so a row already on one of these entities keeps it. The
   * provider path and {@code CascadeResolver.assertSame}'s door read it; {@link #candidateEntityIds}
   * and {@link #assertedSameEntityIds} carry the same predicate in their own SQL.
   */
  public Set<UUID> holdingSourceElsewhere(Connection c, long tenantId, String entityType, String source,
      String sourceId, List<UUID> entityIds) {
    if (entityIds.isEmpty()) return Set.of();
    String sql = "SELECT DISTINCT entity_id FROM core.entity_identity "
        + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id <> ? AND entity_id = ANY(?)";
    return new HashSet<>(Jdbc.query(c, "EntityIdentityRepository.holdingSourceElsewhere", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setArray(5, c.createArrayOf("uuid", entityIds.toArray()));
    }, rs -> (UUID) rs.getObject(1)));
  }

  /**
   * Ambiguous crosswalk rows for one entity type, each enriched with the candidates/discarded/
   * decidedBy of its most recent ledger event (#622's ambiguity API) — the {@code LATERAL} join
   * picks exactly one evidence row per identity, the newest by {@code (occurred_at, id)}, the same
   * tie-break {@link #evidenceHistory} uses so a page reads consistently even when two events
   * landed in the same instant.
   */
  public List<AmbiguousIdentity> listAmbiguous(
      Connection c, long tenantId, String entityType, int limit, int offset) {
    String sql = "SELECT ei.identity_ref, ei.source, ei.source_id, "
        + "ev.candidates::text, ev.discarded_candidates::text, ev.set_aside::text, ev.decided_by, ev.occurred_at "
        + "FROM core.entity_identity ei "
        + "JOIN LATERAL ("
        + "  SELECT candidates, discarded_candidates, set_aside, decided_by, occurred_at FROM core.entity_resolution_evidence e "
        + "  WHERE e.tenant_id = ei.tenant_id AND e.entity_type = ei.entity_type "
        + "    AND e.source = ei.source AND e.source_id = ei.source_id "
        + "  ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1"
        + ") ev ON true "
        + "WHERE ei.tenant_id = ? AND ei.entity_type = ? AND ei.state = 'ambiguous' "
        + "ORDER BY ei.source, ei.source_id LIMIT ? OFFSET ?";
    return Jdbc.query(c, "EntityIdentityRepository.listAmbiguous", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setInt(3, limit);
      ps.setInt(4, offset);
    }, rs -> new AmbiguousIdentity(
        (UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
        parseUuidList(rs.getString(4)), parseProposedCandidates(rs.getString(5)),
        parseSetAside(rs.getString(6)), rs.getString(7), rs.getObject(8, OffsetDateTime.class)));
  }

  /**
   * The full decision history of one source identity, oldest first (#622's review API) — {@code
   * ORDER BY occurred_at, id}: {@code id} is a pure tie-break for events that land in the same
   * instant (a same-transaction cascade can write several), never a substitute ordering — without
   * it, two ties could read in either order from one call to the next. For a {@code resolved}
   * outcome specifically, {@code candidates} can now have 2 entries when it's an authorized-split
   * "lost link" event (icreated/lumnik#737, {@code [new entity, departed entity]}) — index 0 is
   * always "the entity this event resolved to," never assume {@code candidates} is a
   * single-element list for {@code resolved} outcomes.
   */
  public List<EvidenceEvent> evidenceHistory(
      Connection c, long tenantId, String entityType, String source, String sourceId) {
    String sql = "SELECT e.rule_declared_id, e.rule_version, e.provider, e.candidates::text, "
        + "e.discarded_candidates::text, e.set_aside::text, e.outcome, e.decided_by, e.run_id, e.occurred_at, "
        + "e.asserted_by, u.login, e.assertion_reason "
        + "FROM core.entity_resolution_evidence e "
        + "LEFT JOIN identity.app_user u ON u.tenant_id = e.tenant_id AND u.id::text = e.asserted_by "
        + "WHERE e.tenant_id = ? AND e.entity_type = ? AND e.source = ? AND e.source_id = ? "
        + "ORDER BY e.occurred_at, e.id";
    return Jdbc.query(c, "EntityIdentityRepository.evidenceHistory", sql, ps -> {
      ps.setLong(1, tenantId);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
    }, rs -> new EvidenceEvent(
        rs.getString(1), rs.getString(2), rs.getString(3),
        parseUuidList(rs.getString(4)), parseProposedCandidates(rs.getString(5)),
        parseSetAside(rs.getString(6)), EntityResolutionState.fromSql(rs.getString(7)), rs.getString(8),
        (UUID) rs.getObject(9), rs.getObject(10, OffsetDateTime.class), rs.getString(11), rs.getString(12), rs.getString(13)));
  }

  private static List<UUID> parseUuidList(String json) {
    return parseJson(json, UUID_LIST);
  }

  private static List<ProposedCandidate> parseProposedCandidates(String json) {
    return parseJson(json, PROPOSED_CANDIDATE_LIST);
  }

  private static List<SetAside> parseSetAside(String json) {
    return parseJson(json, SET_ASIDE_LIST);
  }

  /**
   * The payload never travels in the exception message (Copilot review on PR #645) — {@code
   * candidates}/{@code discarded_candidates} is an append-only ledger that could grow large or
   * later carry a field an operator should not have to see echoed into a log a message can reach
   * — same posture as {@link io.lumnik.hub.sql.SqlFailureText}: log the full detail once, here,
   * server-side, and let the exception itself say only that it happened.
   */
  private static <T> List<T> parseJson(String json, TypeReference<List<T>> type) {
    if (json == null) return List.of();
    try {
      return JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      LOG.errorf(e, "malformed evidence JSON: %s", json);
      throw new IllegalStateException("malformed evidence JSON — see server log for the payload", e);
    }
  }
}

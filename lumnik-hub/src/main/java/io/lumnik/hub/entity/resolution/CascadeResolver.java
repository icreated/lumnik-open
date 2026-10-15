package io.lumnik.hub.entity.resolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.extraction.spi.entity.CandidateProvider;
import io.lumnik.extraction.spi.entity.CandidateProviderException;
import io.lumnik.extraction.spi.entity.CandidateQuery;
import io.lumnik.extraction.spi.entity.ProposedCandidate;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The native ordered cascade and decision policy (design doc §7, #613 lot #617). Runs the declared
 * cascade rule by rule against one source row, upserts the crosswalk and its fingerprints, and
 * appends the evidence ledger — all inside one transaction, so a crash mid-decision leaves no
 * half-state (idempotent replay: the same snapshot and rule versions produce a no-op upsert).
 *
 * <p><b>The stop rule.</b> The cascade stops only on an unambiguous accepted decision: an ambiguity
 * does not stop it, so a later, more specific rule can still arbitrate — the earlier ambiguity stays
 * in the ledger, nothing merges silently. A {@code propose:} rule always yields {@code
 * unresolved_provider} in this lot: the {@code CandidateProvider} seam is #621, so until then every
 * provider is unavailable by construction.
 *
 * <p><b>Never silently recycle a resolved link.</b> If this row was already {@code resolved} and
 * re-evaluation would now name a different entity, the change is refused and recorded as {@code
 * ambiguous} for review (design doc §10) — this covers a stale fingerprint index or a rule-version
 * bump alike without comparing versions explicitly: only "did the entity_id change" matters. The
 * comparison is against the ledger's last genuine {@code resolved} outcome, not only the live
 * crosswalk row — once a refusal has already flipped the row to {@code ambiguous}, the live row no
 * longer has an entity_id to compare against, and a replay of the same conflicting data must still
 * refuse rather than quietly settle on the rejected candidate. A previously-resolved row naturally
 * losing its resolution to {@code ambiguous} or {@code unresolved_*} is not refused the same way
 * (nothing is being silently swapped to a wrong entity), but the ledger still records the entity_id
 * it used to carry, so that fact is never lost.
 *
 * <p><b>Human assertions (#619, design doc §7) are the one authorized exception.</b> Consulted as
 * step 0 — before any rule, outranking every one of them — a {@code same} assertion whose partner
 * is already resolved decides this row outright and the cascade below is not even run; this is
 * also the one path the "never silently recycle" guard above does not apply to, since moving a
 * canonical id is exactly what an assertion is for. A {@code distinct} assertion is a veto instead:
 * every rule's raw candidate list has its asserted-distinct partners removed before the size-based
 * decision, every pass — which is what makes it survive replay rather than holding only once.
 * Retracting the specific {@code same} assertion that produced a merge (calling {@code
 * assertDistinct} on the same pair) is the OTHER authorized exception (icreated/lumnik#737): it lets
 * the row that was moved in by that assertion move back out on its next {@code resolve()} pass,
 * proven order-independently via {@link EntityIdentityRepository#verdictOf}. A {@code same}
 * assertion only moves a row whose entity_id changes; a row already on the target entity keeps its
 * own provenance (icreated/lumnik#739), and the anchor of a retracted assertion keeps its entity id
 * in any reprocessing order — except when the moved row is held on the shared entity by another
 * live {@code same} assertion: it cannot leave, so the anchor surfaces as AMBIGUOUS for review.
 */
@ApplicationScoped
public class CascadeResolver {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String AUTOMATIC = "automatic";
  private static final String ASSERTED = "asserted";

  @Inject EntityFingerprintService fingerprints;
  @Inject EntityIdentityRepository repository;
  @Inject Instance<CandidateProvider> providers;
  @Inject SearchProjectionMarkStore markStore;

  private record RuleEvent(String ruleDeclaredId, String ruleVersion, String provider,
      List<UUID> candidates, List<ProposedCandidate> discarded, EntityResolutionState outcome,
      UUID assertedPartnerRef) {
    /** Every non-propose event has nothing to discard — {@code exact:} rules never call a provider. */
    RuleEvent(String ruleDeclaredId, String ruleVersion, String provider, List<UUID> candidates,
        EntityResolutionState outcome) {
      this(ruleDeclaredId, ruleVersion, provider, candidates, List.of(), outcome, null);
    }
    /** Every discarded-carrying event (a {@code propose:} rule) carries no assertion provenance
     *  either — only the bare RESOLVED event a {@code sameAssertionResolved} decision produces ever
     *  does (icreated/lumnik#737). */
    RuleEvent(String ruleDeclaredId, String ruleVersion, String provider, List<UUID> candidates,
        List<ProposedCandidate> discarded, EntityResolutionState outcome) {
      this(ruleDeclaredId, ruleVersion, provider, candidates, discarded, outcome, null);
    }
  }

  public ResolutionResult resolve(long tenantId, String entityType, String source, String sourceId,
      List<EntityManifest.CascadeRule> cascade, Map<String, String> rawFieldValues, UUID runId) {
    return repository.inTransaction(c ->
        resolveWithin(c, tenantId, entityType, source, sourceId, cascade, rawFieldValues, runId));
  }

  /** "These two source identities are the same" (#619) — one of the two doors into the correction path. */
  public void assertSame(long tenantId, String entityType, UUID leftRef, UUID rightRef,
      String assertedBy, String reason) {
    requireAssertionArgs(entityType, leftRef, rightRef, assertedBy);
    repository.inTransaction(c -> {
      Optional<EntityIdentityRepository.Seat> left = repository.seatOf(c, tenantId, entityType, leftRef);
      Optional<EntityIdentityRepository.Seat> right = repository.seatOf(c, tenantId, entityType, rightRef);
      if (left.isPresent() && right.isPresent()) {
        refuseSameSource(left.get(), right.get());
        refuseImpossibleMerge(c, tenantId, entityType, left.get(), right.get());
      }
      repository.assertSame(c, tenantId, entityType, leftRef, rightRef, assertedBy, reason);
      return null;
    });
  }

  /**
   * {@code core.entity_identity}'s own {@code uq_entity_identity_cardinality} constraint ("at most
   * one identity per source") makes converging two rows of the SAME source onto one {@code
   * entity_id} structurally impossible (icreated/lumnik#735) — refused upfront here, before the
   * assertion is ever written, rather than recording it and letting the merge crash silently later
   * when either row is next reprocessed. A ref this tenant/entityType has not seen yet has no
   * source to compare and is left to the ordinary {@code fk_entity_assertion*} refusal downstream.
   */
  private static void refuseSameSource(EntityIdentityRepository.Seat left, EntityIdentityRepository.Seat right) {
    if (left.source().equals(right.source())) {
      throw new SameSourceAssertionException(left.source());
    }
  }

  /**
   * The same constraint, one step further (#889): two refs of DIFFERENT sources can still be
   * impossible to merge. The left row cannot join the right's entity when that entity already holds
   * another row of the left's source, and the other way round. One blocked direction is fine —
   * {@link #resolveWithin} lets the side that can move do it, whichever row is reprocessed first.
   * Both blocked, and no order would ever honour the assertion: refused here rather than recorded as
   * a 204 that silently never takes effect. A ref with no resolved entity yet has nothing to judge
   * against and is let through.
   */
  private void refuseImpossibleMerge(Connection c, long tenantId, String entityType,
      EntityIdentityRepository.Seat left, EntityIdentityRepository.Seat right) {
    if (left.entityId() == null || right.entityId() == null || left.entityId().equals(right.entityId())) return;
    if (cannotJoin(c, tenantId, entityType, left, right.entityId())
        && cannotJoin(c, tenantId, entityType, right, left.entityId())) {
      throw new SameSourceAssertionException(left.source(), right.source());
    }
  }

  /** The row at {@code seat} cannot join {@code entityId}: that entity already holds another row of its source. */
  private boolean cannotJoin(Connection c, long tenantId, String entityType, EntityIdentityRepository.Seat seat,
      UUID entityId) {
    return !repository.holdingSourceElsewhere(
        c, tenantId, entityType, seat.source(), seat.sourceId(), List.of(entityId)).isEmpty();
  }

  /** "These two are not" (#619) — the other door; see {@link EntityIdentityRepository#assertDistinct}. */
  public void assertDistinct(long tenantId, String entityType, UUID leftRef, UUID rightRef,
      String assertedBy, String reason) {
    requireAssertionArgs(entityType, leftRef, rightRef, assertedBy);
    repository.inTransaction(c -> {
      repository.assertDistinct(c, tenantId, entityType, leftRef, rightRef, assertedBy, reason);
      return null;
    });
  }

  /**
   * A public entry point ("door") must fail fast with a clear message rather than round-trip to
   * the database only to hit a CHECK or NOT NULL constraint: same ref on both sides would make
   * {@code LEAST}/{@code GREATEST} produce equal left/right, violating {@code
   * ck_entity_assertion_order}'s STRICT less-than; a blank {@code entityType} or {@code assertedBy}
   * would hit their own NOT NULL columns instead. {@code reason} is the one nullable column and is
   * deliberately not checked here.
   */
  private static void requireAssertionArgs(String entityType, UUID leftRef, UUID rightRef, String assertedBy) {
    if (entityType == null || entityType.isBlank())
      throw new IllegalArgumentException("an assertion requires a non-blank entityType");
    if (assertedBy == null || assertedBy.isBlank())
      throw new IllegalArgumentException("an assertion requires a non-blank assertedBy");
    if (leftRef == null || rightRef == null)
      throw new IllegalArgumentException("an assertion requires two non-null identity refs");
    if (leftRef.equals(rightRef))
      throw new IllegalArgumentException("an identity cannot be asserted same/distinct with itself: " + leftRef);
  }

  /**
   * Entities this row must never join because of a {@code distinct} assertion — minus any partner that
   * is provably the row MOVED onto this row's own entity by a {@code same} assertion since retracted
   * (icreated/lumnik#739): that partner is the one that must leave, so vetoing the shared entity here
   * would evict the anchor from its own id. Proof is the partner's own last resolved evidence —
   * {@code decided_by='asserted'}, naming THIS row as its partner, on this same entity — the pair's
   * verdict being {@code distinct} already, because that is what the partner query selects. The
   * partner must also be held by no live {@code same} assertion of its own (a third row moved onto it
   * would keep it on the entity), i.e. it is provably going to leave; otherwise the veto stays and the
   * conflict surfaces (this row goes AMBIGUOUS) instead of both rows silently sharing the entity.
   */
  private List<UUID> distinctVetoEntityIds(Connection c, long tenantId, String entityType, UUID myRef,
      UUID priorResolvedEntityId) {
    return repository.assertedDistinctPartners(c, tenantId, entityType, myRef).stream()
        .filter(p -> !(p.entityId().equals(priorResolvedEntityId) && isDepartingRow(c, tenantId, entityType, p, myRef)))
        .map(EntityIdentityRepository.AssertedPartner::entityId).distinct().toList();
  }

  private boolean isDepartingRow(Connection c, long tenantId, String entityType,
      EntityIdentityRepository.AssertedPartner partner, UUID myRef) {
    return repository.lastResolvedEvidenceOfRef(c, tenantId, entityType, partner.partnerRef())
        .filter(e -> ASSERTED.equals(e.decidedBy()) && myRef.equals(e.assertedPartnerRef())
            && e.entityId().equals(partner.entityId()))
        .isPresent()
        && !heldOnEntityByOtherSame(c, tenantId, entityType, partner);
  }

  /**
   * True when the partner's own live {@code same} partners resolve to EXACTLY the partner's entity —
   * another row genuinely holds it there, so it cannot leave. Partners resolving elsewhere (or to
   * several entities, which sends the partner AMBIGUOUS) do take it off the entity, so they do not
   * count.
   */
  private boolean heldOnEntityByOtherSame(Connection c, long tenantId, String entityType,
      EntityIdentityRepository.AssertedPartner partner) {
    List<UUID> held = repository.assertedSameEntityIds(c, tenantId, entityType, partner.partnerRef()).stream()
        .map(EntityIdentityRepository.AssertedPartner::entityId).distinct().toList();
    return held.size() == 1 && held.get(0).equals(partner.entityId());
  }

  /**
   * The partner recorded by a carried-forward decision, kept only while that pair's verdict is still
   * {@code same}: a retracted pair's provenance must not keep authorizing (or hiding) a split it no
   * longer describes.
   */
  private UUID carriedPartnerRef(Connection c, long tenantId, String entityType, UUID myRef,
      EntityIdentityRepository.LastResolvedEvidence priorEvidence) {
    if (priorEvidence == null || priorEvidence.assertedPartnerRef() == null) {
      return null;
    }
    return repository.verdictOf(c, tenantId, entityType, myRef, priorEvidence.assertedPartnerRef())
        .filter("same"::equals).isPresent() ? priorEvidence.assertedPartnerRef() : null;
  }

  private ResolutionResult resolveWithin(Connection c, long tenantId, String entityType, String source,
      String sourceId, List<EntityManifest.CascadeRule> cascade, Map<String, String> rawFieldValues,
      UUID runId) {

    List<RuleEvent> events = new ArrayList<>();

    // A human assertion only ever addresses a row the system has already seen — identity_ref is
    // minted on first upsert (design doc §3) — so on a row's very first pass there is nothing yet
    // to assert about, and every assertion check below is a no-op.
    Optional<EntityIdentityRepository.ExistingIdentity> existing =
        repository.find(c, tenantId, entityType, source, sourceId);
    // Already without the partners whose entity holds another row of this source (#889) — the side
    // that moves is then the partner; see assertedSameEntityIds and refuseImpossibleMerge.
    EntityIdentityRepository.SamePartners samePartners = existing.isPresent()
        ? repository.assertedSamePartners(c, tenantId, entityType, existing.get().identityRef())
        : new EntityIdentityRepository.SamePartners(List.of(), List.of());
    List<EntityIdentityRepository.AssertedPartner> assertedSamePartners = samePartners.applicable();
    // What this call set aside, for the evidence ledger (#920): the partners above, then each rule's.
    List<EntityIdentityRepository.SetAside> setAside = new ArrayList<>();
    samePartners.setAside().stream().map(EntityIdentityRepository.AssertedPartner::entityId).distinct()
        .forEach(id -> setAside.add(EntityIdentityRepository.SetAside.sameSource(id, null)));
    List<UUID> assertedSameEntityIds = assertedSamePartners.stream()
        .map(EntityIdentityRepository.AssertedPartner::entityId).distinct().toList();
    // Two 'same' assertions on this identity can disagree (A~B and A~C, B and C resolved to
    // different entities) — picking either arbitrarily could silently name a canonical id no one
    // actually decided, and differ between replays. Only a SINGLE distinct answer is a valid
    // override; more than one is itself a conflict, surfaced as ambiguous below, same as the
    // cascade's own ambiguity.
    Optional<UUID> sameAssertionResolved =
        assertedSameEntityIds.size() == 1 ? Optional.of(assertedSameEntityIds.get(0)) : Optional.empty();
    boolean sameAssertionConflict = assertedSameEntityIds.size() >= 2;
    boolean distinctVetoApplied = false;

    // The single partner ref responsible for sameAssertionResolved, when unambiguous
    // (icreated/lumnik#737): present only when exactly one assertion row contributed, not merely
    // one distinct entity id — two different partner refs coincidentally resolving to the same
    // entity leave this empty on purpose (assertedSamePartners.size() would be 2, even though
    // assertedSameEntityIds.size() collapses to 1).
    Optional<UUID> assertedPartnerRef = assertedSamePartners.size() == 1
        ? Optional.of(assertedSamePartners.get(0).partnerRef())
        : Optional.empty();

    // priorResolvedEntityId and explicitlyRetracted are computed HERE, before phase 2 runs — not
    // only inside the post-hoc guard below — because phase 2's own candidate filtering needs to
    // veto priorResolvedEntityId directly (PR review finding on #737): the distinct veto only covers
    // a partner that is CURRENTLY 'resolved', so if the partner has itself already gone ambiguous,
    // that veto silently returns empty and this row's own stale self-match — still pointing at the
    // entity it was explicitly retracted from — would otherwise slip straight through uncontested.
    // verdictOf itself was always order-independent; the bug was that it was only ever consulted
    // AFTER a naturalState the live-state-dependent veto could already have corrupted.
    //
    // The live crosswalk row is not enough on its own for priorResolvedEntityId: once a refused
    // swap has already flipped it to ambiguous, comparing against the (now null) live entity_id
    // would let the very next replay of the same conflicting data quietly settle on the rejected
    // candidate — the refusal would only ever hold for one call. The ledger's last genuine
    // "resolved" outcome is what makes it stick across every later replay, not just the one call
    // that made it.
    boolean liveResolved = existing.isPresent() && "resolved".equals(existing.get().state());
    UUID liveEntityId = liveResolved ? existing.get().entityId() : null;
    // A 'same' assertion is symmetric, its effect is not (icreated/lumnik#739): only the row whose
    // entity_id CHANGES is moved by it. The comparison is against the LIVE entity, not the ledger
    // fallback: a row healed from ambiguous (live entity NULL) goes NULL -> E because of the human,
    // so it is the moved row.
    boolean assertionMovesRow =
        sameAssertionResolved.isPresent() && !sameAssertionResolved.get().equals(liveEntityId);
    boolean carriedForward = sameAssertionResolved.isPresent() && !assertionMovesRow;
    // Read once, and only when something reads it: the prior decision's provenance, and — when the live
    // row is not resolved — the entity it used to carry. A live row moved by an assertion needs neither.
    EntityIdentityRepository.LastResolvedEvidence lastEvidence = liveResolved && assertionMovesRow ? null
        : repository.lastResolvedEvidence(c, tenantId, entityType, source, sourceId).orElse(null);
    UUID priorResolvedEntityId = liveResolved ? liveEntityId
        : lastEvidence != null ? lastEvidence.entityId() : null;
    // Defense-in-depth: the ledger's own recorded entity must agree with the prior entity before it is
    // trusted — they agree by construction today, but this keeps every reader self-contained rather
    // than relying on that invariant implicitly.
    EntityIdentityRepository.LastResolvedEvidence priorEvidence =
        existing.isPresent() && lastEvidence != null && lastEvidence.entityId().equals(priorResolvedEntityId)
            ? lastEvidence : null;

    // The distinct veto is computed once priorResolvedEntityId is known: it covers every currently
    // resolved distinct partner EXCEPT the one provably moved onto THIS row's entity and about to
    // leave it, so the anchor is never evicted from its own id (#739).
    List<UUID> assertedDistinctEntityIds = existing.isPresent()
        ? distinctVetoEntityIds(c, tenantId, entityType, existing.get().identityRef(), priorResolvedEntityId)
        : List.of();

    // The one authorized way to move AWAY from a resolved link without a human assertion "moving
    // in" (icreated/lumnik#737, symmetric to sameAssertionResolved's own "moving in" exemption
    // above): the row's PRIOR decision was itself decided_by='asserted', with a recorded partner
    // ref, and the assertion table's CURRENT verdict for that exact pair is now 'distinct' — proof
    // the specific assertion that produced the prior merge has since been explicitly retracted.
    // Reading verdictOf against the pair (never the partner's live resolution state) is what keeps
    // this order-independent: it depends only on the assertion table, not on whether the partner
    // row has itself been reprocessed yet. Gated on priorResolvedEntityId != null (skips the common
    // "first-ever resolve" path) and assertedSameEntityIds.isEmpty() (phase 2 — the only place this
    // value is read — never even runs otherwise, since a live 'same' assertion already outranks
    // every rule; revokesExistingResolution is also unconditionally false whenever
    // sameAssertionResolved holds, so explicitlyRetracted genuinely cannot matter there). Still runs
    // on every ordinary reprocess of an already-resolved row now, not only on the rare "about to
    // refuse" path — the unavoidable cost of catching the veto before phase 2 rather than after.
    boolean explicitlyRetracted = false;
    if (priorResolvedEntityId != null && existing.isPresent() && assertedSameEntityIds.isEmpty()) {
      explicitlyRetracted = priorEvidence != null && ASSERTED.equals(priorEvidence.decidedBy())
          && priorEvidence.assertedPartnerRef() != null
          && repository.verdictOf(c, tenantId, entityType, existing.get().identityRef(),
                 priorEvidence.assertedPartnerRef()).filter("distinct"::equals).isPresent();
    }

    // A row already on the assertion's entity keeps whatever provenance its own last resolved decision
    // carried — 'automatic' for an anchor, 'asserted' + partner ref for a moved row reprocessed while
    // the merge is active (so its later split stays authorized). A moved row whose pair was since
    // retracted keeps decided_by='asserted' but drops the partner ref; harmless, because
    // explicitlyRetracted needs a non-null ref to authorize anything.
    String carriedDecidedBy = carriedForward && priorEvidence != null ? priorEvidence.decidedBy() : AUTOMATIC;

    // The set of entities this row's own candidates must never include: partners it's explicitly
    // asserted distinct from (and currently resolved, minus the moved-partner exemption above), PLUS
    // — when explicitlyRetracted — the entity it was itself just authorized to move away from. Folding the two into one set keeps
    // the existing distinctVetoApplied/decided_by='asserted' attribution logic in phase 2 correct
    // without any special-casing: stripping priorResolvedEntityId this way is exactly as much "a
    // human assertion changed this rule's decision" as the ordinary distinct veto already is.
    List<UUID> effectiveDistinctVeto = assertedDistinctEntityIds;
    if (explicitlyRetracted) {
      effectiveDistinctVeto = new ArrayList<>(assertedDistinctEntityIds);
      effectiveDistinctVeto.add(priorResolvedEntityId);
    }

    // Phase 1 — materialize every exact rule's key for THIS pass, unconditionally. A later rule's
    // key must reflect the row's current data even when the decision phase below never needs to
    // look that far: the decision loop stops at the first accept, but a stale key left behind by a
    // rule it never reached would keep matching a future, unrelated row against data this identity
    // no longer carries (design doc §10 — a stale index must never manufacture a wrong merge).
    Map<String, RuleIdentity> keysComputed = new LinkedHashMap<>();
    Map<String, byte[]> fingerprintsComputed = new LinkedHashMap<>();
    List<String> staleRuleIds = new ArrayList<>();

    for (EntityManifest.CascadeRule rule : cascade) {
      if (rule.exact() == null) continue;
      EntityManifest.Exact exact = rule.exact();
      Optional<byte[]> fp = fingerprints.fingerprint(
          tenantId, entityType, rule.id(), exact.fields(), exact.normalize(), rawFieldValues);
      if (fp.isEmpty()) {
        staleRuleIds.add(rule.id());
        continue;
      }
      keysComputed.put(rule.id(), fingerprints.ruleIdentity(rule.id(), exact.fields(), exact.normalize()));
      fingerprintsComputed.put(rule.id(), fp.get());
    }

    // Phase 2 — the decision loop: reuses the fingerprints computed above, stopping only on an
    // unambiguous accepted decision (the stop rule, design doc §7). Independent of phase 1 above.
    // Skipped entirely when a 'same' assertion already outranks every rule (step 0) — the cascade
    // is not even consulted, since the assertion's answer wins regardless of what it would have
    // found, whether that answer is a clean override or itself a conflict.
    boolean sawAnyKey = !keysComputed.isEmpty();
    boolean sawAmbiguity = false;
    boolean sawProviderUnavailable = false;
    RuleEvent acceptedEvent = null;
    // #622's observability — a real call to a wired provider, and how many of those failed;
    // "no bean wired at all" (provider == null below) is neither.
    int providerCalls = 0;
    int providerFailures = 0;

    if (assertedSameEntityIds.isEmpty()) {
      for (EntityManifest.CascadeRule rule : cascade) {
        if (rule.exact() != null) {
          RuleIdentity identity = keysComputed.get(rule.id());
          if (identity == null) continue; // this rule's fields aren't usable for this row.
          byte[] fp = fingerprintsComputed.get(rule.id());

          // Already without the entities holding another row of this source (#889), so the attribution
          // below stays about the human veto alone.
          EntityIdentityRepository.KeyMatches matches = repository.candidateEntityIds(
              c, tenantId, entityType, source, sourceId, rule.id(), identity.ruleVersion(), fp);
          matches.setAside().forEach(id -> setAside.add(EntityIdentityRepository.SetAside.sameSource(id, rule.id())));
          List<UUID> rawCandidates = matches.joinable();
          List<UUID> candidates = new ArrayList<>(rawCandidates);
          // The distinct veto: an asserted-distinct partner's entity, and (when explicitlyRetracted)
          // the entity this row was itself retracted from, are excluded at every rule, every pass —
          // the same rule would otherwise re-find either on the very next replay.
          candidates.removeAll(effectiveDistinctVeto);
          // decided_by='asserted' only when the veto crossed THIS rule's own accept/no-accept
          // boundary (1->0, or >=2->1): raw>=2 staying >=2 (still ambiguous either way) or raw==1
          // staying 1 (nothing of this rule's own candidates was actually vetoed) means the veto
          // never influenced what got decided here, however much the recorded candidate set
          // itself differs — attributing that to a human assertion would mislead #622's future
          // observability into a human decision that never actually happened.
          boolean rawWouldAccept = rawCandidates.size() == 1;
          boolean filteredStillAccepts = candidates.size() == 1;
          boolean rawWasAmbiguous = rawCandidates.size() >= 2;
          boolean filteredStillAmbiguous = candidates.size() >= 2;
          if ((rawWouldAccept && !filteredStillAccepts) || (rawWasAmbiguous && !filteredStillAmbiguous)) {
            distinctVetoApplied = true;
          }

          if (candidates.size() == 1) {
            acceptedEvent = new RuleEvent(rule.id(), identity.ruleVersion(), null, candidates,
                EntityResolutionState.RESOLVED);
            break; // the cascade stops only on an unambiguous accepted decision.
          } else if (candidates.size() >= 2) {
            sawAmbiguity = true;
            events.add(new RuleEvent(rule.id(), identity.ruleVersion(), null, candidates,
                EntityResolutionState.AMBIGUOUS));
          }
          // 0 candidates: inconclusive — this rule's key matches nobody yet. Continue the cascade.
        } else {
          EntityManifest.Propose propose = rule.propose();
          CandidateProvider provider = selectProvider(propose.provider());
          List<ProposedCandidate> proposed = null;
          if (provider != null) {
            providerCalls++;
            proposed = askProvider(provider, tenantId, entityType, rule.id(), rawFieldValues);
            if (proposed == null) providerFailures++;
          }
          if (proposed == null) {
            sawProviderUnavailable = true;
            events.add(new RuleEvent(rule.id(), null, propose.provider(), List.of(),
                EntityResolutionState.UNRESOLVED_PROVIDER));
          } else {
            // The decision stays in the hub (design doc §9, invariant 7): a provider only proposes,
            // it never crosses acceptAbove/reviewAbove itself.
            // A proposed entity already holding another row of this source can never take this one
            // (#889, see EntityIdentityRepository#holdingSourceElsewhere): set aside like an exact
            // rule's, before the tiers, so vetoChangedOutcome stays about the human veto alone.
            Set<UUID> holders = repository.holdingSourceElsewhere(c, tenantId, entityType, source, sourceId,
                proposed.stream().map(ProposedCandidate::entityId).toList());
            holders.forEach(id -> setAside.add(EntityIdentityRepository.SetAside.sameSource(id, rule.id())));
            CandidateTiers tiers = tierProposedCandidates(c, tenantId, entityType,
                proposed.stream().filter(p -> !holders.contains(p.entityId())).toList(),
                propose, effectiveDistinctVeto);
            if (tiers.vetoChangedOutcome()) {
              distinctVetoApplied = true;
            }
            List<UUID> considered = new ArrayList<>(tiers.strong());
            considered.addAll(tiers.review());
            if (tiers.strong().size() == 1 && tiers.review().isEmpty()) {
              acceptedEvent = new RuleEvent(rule.id(), null, propose.provider(), considered,
                  tiers.discarded(), EntityResolutionState.RESOLVED);
              break; // the cascade stops only on an unambiguous accepted decision.
            } else if (!considered.isEmpty()) {
              sawAmbiguity = true;
              events.add(new RuleEvent(rule.id(), null, propose.provider(), considered,
                  tiers.discarded(), EntityResolutionState.AMBIGUOUS));
            } else if (!tiers.discarded().isEmpty()) {
              // Nothing usable, but the provider DID answer with something worth auditing — unlike
              // an exact: rule's silent 0-candidate case, a stale-index discard must survive (V51),
              // and this row should be retried once the index is fixed, exactly like "provider
              // unavailable" already is (#620's retry query targets unresolved_provider rows).
              sawProviderUnavailable = true;
              events.add(new RuleEvent(rule.id(), null, propose.provider(), List.of(),
                  tiers.discarded(), EntityResolutionState.UNRESOLVED_PROVIDER));
            }
            // considered and discarded both empty: this rule found nothing at all — inconclusive,
            // continue the cascade, same as an exact: rule's own 0-candidate case.
          }
        }
      }
    }

    EntityResolutionState naturalState;
    UUID naturalEntityId;
    boolean freshMintCandidate = false;
    if (sameAssertionResolved.isPresent()) {
      naturalState = EntityResolutionState.RESOLVED;
      naturalEntityId = sameAssertionResolved.get();
    } else if (sameAssertionConflict) {
      naturalState = EntityResolutionState.AMBIGUOUS;
      naturalEntityId = null;
    } else if (acceptedEvent != null) {
      naturalState = EntityResolutionState.RESOLVED;
      naturalEntityId = acceptedEvent.candidates().get(0);
    } else if (sawAmbiguity) {
      naturalState = EntityResolutionState.AMBIGUOUS;
      naturalEntityId = null;
    } else if (sawProviderUnavailable) {
      naturalState = EntityResolutionState.UNRESOLVED_PROVIDER;
      naturalEntityId = null;
    } else if (sawAnyKey) {
      naturalState = EntityResolutionState.RESOLVED; // no candidate anywhere — an unseen entity.
      naturalEntityId = UUID.randomUUID();
      freshMintCandidate = true;
    } else {
      naturalState = EntityResolutionState.UNRESOLVED_NO_RULE;
      naturalEntityId = null;
    }

    // A previously-resolved link needs checking against EVERY natural outcome, not only a fresh
    // RESOLVED one: losing a resolution to ambiguous/unresolved is legitimate on its own (design
    // doc §10's other failure modes), but the ledger must not silently drop the entity_id it had.
    // priorResolvedEntityId and explicitlyRetracted were both already computed above, before phase
    // 2 — reused here unchanged.
    //
    // A resolved 'same' assertion override is exempt from this guard entirely (#619): it is ONE
    // authorized path that MAY move a canonical id — retracting the specific assertion that
    // produced a prior merge is the other (icreated/lumnik#737, explicitlyRetracted above). A
    // CONFLICTING same-assertion is NOT exempt — its natural outcome is already ambiguous, not a
    // different resolved entity, so the guard's own bookkeeping (recording what this row used to
    // resolve to) still applies normally.
    EntityResolutionState finalState = naturalState;
    UUID finalEntityId = naturalEntityId;
    boolean revokesExistingResolution = sameAssertionResolved.isEmpty() && priorResolvedEntityId != null
        && !(naturalState == EntityResolutionState.RESOLVED
             && priorResolvedEntityId.equals(naturalEntityId));

    if (naturalState == EntityResolutionState.RESOLVED && revokesExistingResolution && !explicitlyRetracted) {
      // Re-evaluation would silently swap an already-resolved link to a DIFFERENT entity. Refuse
      // it — only a human assertion may move a canonical id (design doc §10), including retracting
      // the SAME assertion that produced the row's own prior resolution (icreated/lumnik#737).
      finalState = EntityResolutionState.AMBIGUOUS;
      finalEntityId = null;
    }

    if (revokesExistingResolution) {
      // Whatever this row's new outcome is, it no longer names the entity it used to — say so
      // explicitly, even though the branch below may also add its own per-outcome event.
      String ruleId = acceptedEvent != null ? acceptedEvent.ruleDeclaredId() : null;
      String ruleVersion = acceptedEvent != null ? acceptedEvent.ruleVersion() : null;
      List<UUID> lostLink = new ArrayList<>();
      if (finalState == EntityResolutionState.RESOLVED) {
        // An authorized split (icreated/lumnik#737): candidates ->> 0 must be the entity this row
        // now resolves to — lastResolvedEvidence's own positional convention depends on it. The
        // departed entity is still audited, just not at index 0. sameAssertionConflict can never be
        // true here (it always forces naturalState = AMBIGUOUS upstream), so the two shapes below
        // never compete.
        lostLink.add(finalEntityId);
        lostLink.add(priorResolvedEntityId);
      } else {
        lostLink.add(priorResolvedEntityId);
        if (sameAssertionConflict) {
          // The reason this became ambiguous IS the conflicting assertions — without them here the
          // ledger would show only the entity this row used to carry, never why it stopped, making
          // the ambiguity unreviewable from the evidence alone.
          lostLink.addAll(assertedSameEntityIds);
        } else if (naturalEntityId != null) {
          // finalEntityId is always null here (the guard above clears it whenever natural was
          // RESOLVED and not explicitlyRetracted) — naturalEntityId is the only other place a
          // rejected candidate could be sitting.
          lostLink.add(naturalEntityId);
        }
      }
      // A propose: rule's own discarded (stale-index) candidates from the SAME pass that produced
      // acceptedEvent must survive whatever this row's outcome ends up being — refusal or an
      // authorized split alike — they don't stop being a discrepancy worth auditing either way.
      List<ProposedCandidate> discardedFromAccept =
          acceptedEvent != null ? acceptedEvent.discarded() : List.of();
      events.add(new RuleEvent(ruleId, ruleVersion, null, lostLink, discardedFromAccept, finalState));
    } else if (acceptedEvent != null) {
      events.add(acceptedEvent);
    } else if (naturalState == EntityResolutionState.RESOLVED) {
      // Either a genuine fresh mint (no assertion involved) or a sameAssertionResolved decision: a
      // "moving in" one carries the assertion's partner ref, a carried-forward one the prior
      // decision's own ref while its pair is still 'same' (icreated/lumnik#737, #739).
      UUID eventAssertedPartnerRef = !sameAssertionResolved.isPresent() ? null
          : assertionMovesRow ? assertedPartnerRef.orElse(null)
              : carriedPartnerRef(c, tenantId, entityType, existing.get().identityRef(), priorEvidence);
      events.add(new RuleEvent(null, null, null, List.of(naturalEntityId), List.of(),
          EntityResolutionState.RESOLVED, eventAssertedPartnerRef));
    } else if (naturalState == EntityResolutionState.UNRESOLVED_NO_RULE) {
      events.add(new RuleEvent(null, null, null, List.of(), EntityResolutionState.UNRESOLVED_NO_RULE));
    } else if (sameAssertionConflict) {
      // Ambiguous by way of the assertions themselves (no prior link to also record) — the cascade
      // never ran, so no per-rule event exists for this outcome unlike ordinary cascade ambiguity.
      events.add(new RuleEvent(null, null, null, assertedSameEntityIds, EntityResolutionState.AMBIGUOUS));
    }
    // AMBIGUOUS / UNRESOLVED_PROVIDER without a prior link already have their per-rule events above.

    // One resolve() call makes exactly one decision — decided_by describes the WHOLE call, not a
    // per-event value (a resolved 'same' override or a conflicting one are each the call's only
    // source of events; a 'distinct' veto that changed a rule's candidate list marks the call's
    // single resulting event too, whichever one that turns out to be).
    String decidedBy = carriedForward ? carriedDecidedBy
        : (sameAssertionResolved.isPresent() || sameAssertionConflict || distinctVetoApplied) ? ASSERTED : AUTOMATIC;

    UUID identityRef = repository.upsertIdentity(
        c, tenantId, entityType, source, sourceId, finalState, finalEntityId, decidedBy);

    // #701 phase 5 (design doc §1's correction, §2, §4 trigger row 3): the resolution-driven KEY
    // mark producer. This is the ONLY place entity_id is ever computed or written for a
    // resolution: entity (assertSame/assertDistinct write only a crosswalk assertion fact, no
    // mark of their own — the corrected design doc is explicit on this), so it is also the sole
    // mark point for that mode; row 1' (raw ingestion) produces nothing directly. Runs inside the
    // SAME transaction (connection c) as the entity_identity upsert above and the evidence append
    // below — marking and the identity decision it reflects commit or roll back together.
    markResolutionKeys(c, tenantId, entityType, finalEntityId, priorResolvedEntityId);

    for (Map.Entry<String, RuleIdentity> entry : keysComputed.entrySet()) {
      String ruleId = entry.getKey();
      repository.upsertKey(c, tenantId, entityType, source, sourceId, ruleId,
          entry.getValue().ruleVersion(), fingerprintsComputed.get(ruleId));
    }

    for (String staleRuleId : staleRuleIds) {
      repository.deleteKey(c, tenantId, entityType, source, sourceId, staleRuleId);
    }

    // The set-aside belongs to the call's decision, not to one rule: the last event is that decision.
    String setAsideJson = toJson(setAside, "setAsideJson");
    for (RuleEvent event : events) {
      repository.insertEvidence(c, tenantId, entityType, source, sourceId, identityRef,
          event.ruleDeclaredId(), event.ruleVersion(), event.provider(),
          toJson(event.candidates(), "candidatesJson"), toJson(event.discarded(), "discardedCandidatesJson"),
          event == events.get(events.size() - 1) ? setAsideJson : "[]",
          event.outcome(), decidedBy, runId, event.assertedPartnerRef());
    }

    boolean newlyMinted = freshMintCandidate && finalState == EntityResolutionState.RESOLVED;
    // "entityType:ruleId", not a bare rule id (icreated's review on PR #645, #622): a whole-tenant
    // pass folds every resolution-bearing manifest into ONE shared tally (EntityResolutionPass
    // .runIncremental), and two unrelated entities are free to name a rule the same thing (both
    // declaring "email", say) — a bare id would merge their counts, hiding a dead rule on one
    // entity behind a healthy one on the other.
    Set<String> ruleIds = new LinkedHashSet<>();
    for (RuleEvent event : events) {
      if (event.ruleDeclaredId() != null) ruleIds.add(event.ruleDeclaredId());
    }
    if (newlyMinted) {
      // A fresh mint means every rule that computed a usable key here evaluated the row and found
      // no candidate — it still ran, so it must not disappear from the tally as though it never
      // fired (that "active rule reported as inactive" is exactly the earlier defect).
      ruleIds.addAll(keysComputed.keySet());
    }
    List<String> ruleIdsInvolved = ruleIds.stream().map(id -> entityType + ":" + id).toList();
    return new ResolutionResult(
        finalState, finalEntityId, newlyMinted, providerCalls, providerFailures, ruleIdsInvolved);
  }

  /**
   * Marks the resulting {@code finalEntityId} (when this call resolved to one) and, separately,
   * {@code priorResolvedEntityId} whenever the identity moved away from it — a merge, a split, or a
   * plain loss of resolution back to ambiguous/unresolved all count, since in every one of those
   * cases the old document's contribution is now stale and must be retracted, not only the new one
   * projected. The canonical key for a {@code resolution:} entity is the {@code
   * entity_identity.entity_id} UUID as text (design doc §3) — no {@code
   * io.lumnik.hub.search.mark.MatchKeyEncoding} here, that scheme is {@code match:}-only.
   *
   * <p>Looked up with {@link SearchProjectionMarkStore#validIndexNamesForEntity}, scoped to this one
   * {@code entityType} in SQL — this runs once per resolved source row (potentially every row of a
   * bulk incremental pass), so it must not repeat {@link
   * SearchProjectionMarkStore#validIndexNamesByEntity}'s whole-tenant scan (every entity's every
   * declaration) just to keep one entry.
   *
   * <p>A tenant with no {@code valid} SearchIndex over this entity produces zero marks — marking is
   * purely additive, never a gate on resolution itself (design doc §7 test 4's zero-marks case).
   */
  private void markResolutionKeys(Connection c, long tenantId, String entityType, UUID finalEntityId,
      UUID priorResolvedEntityId) {
    try {
      List<String> indexNames = markStore.validIndexNamesForEntity(c, tenantId, entityType);
      if (indexNames.isEmpty()) return;

      // Both the new and (on a merge/split/lost-resolution) the retracted key belong to the same
      // index, so one batched multi-row INSERT per index covers both — not one INSERT per key.
      List<String> keys = new ArrayList<>(2);
      if (finalEntityId != null) keys.add(finalEntityId.toString());
      if (priorResolvedEntityId != null
          && (finalEntityId == null || !priorResolvedEntityId.equals(finalEntityId))) {
        keys.add(priorResolvedEntityId.toString());
      }
      if (keys.isEmpty()) return;

      for (String indexName : indexNames) {
        markStore.markKeys(c, tenantId, indexName, entityType, keys, "resolution-decision");
      }
    } catch (SQLException e) {
      throw new RuntimeException("CascadeResolver: search projection mark production failed for tenant "
          + tenantId + " entityType " + entityType, e);
    }
  }

  /** No bean named {@code providerName} is an expected, everyday state, not an error — a {@code
   * propose:} rule degrades to {@code unresolved_provider} until one is wired, same posture as
   * {@code Instance<ChatPort>} elsewhere in the hub. */
  private CandidateProvider selectProvider(String providerName) {
    for (CandidateProvider p : providers) {
      if (p.name().equals(providerName)) return p;
    }
    return null;
  }

  /** {@code null} means unavailable right now — folds ANY {@link RuntimeException} a provider
   * throws (not only the declared {@link CandidateProviderException}: a misbehaving provider can
   * throw anything) into the same degrade path as no provider being wired at all (design doc §9).
   * The row's field values are handed over as a defensive, unmodifiable copy — {@code
   * rawFieldValues} is the resolver's own live map, reused by later {@code propose:} rules in the
   * same cascade, and a provider must never be able to corrupt what the next one sees. Not {@link
   * Map#copyOf}: a genuinely {@code NULL} source column value is a legitimate, already-seen entry
   * here (Copilot round 1 on #633), and {@code copyOf} rejects null values outright. */
  private List<ProposedCandidate> askProvider(CandidateProvider provider, long tenantId, String entityType,
      String ruleDeclaredId, Map<String, String> rawFieldValues) {
    Map<String, String> isolated = Collections.unmodifiableMap(new HashMap<>(rawFieldValues));
    try {
      return provider.propose(new CandidateQuery(tenantId, entityType, ruleDeclaredId, isolated));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private record CandidateTiers(
      List<UUID> strong, List<UUID> review, List<ProposedCandidate> discarded, boolean vetoChangedOutcome) {}

  /** Whether {@code strongSize}/{@code reviewSize} would make this rule accept unambiguously. */
  private static boolean wouldAccept(int strongSize, int reviewSize) {
    return strongSize == 1 && reviewSize == 0;
  }

  /** Whether {@code strongSize}/{@code reviewSize} would make this rule ambiguous (design doc §7:
   * a lone review-band candidate forces it too, same as 2+ strong ones). */
  private static boolean wouldBeAmbiguous(int strongSize, int reviewSize) {
    return (strongSize + reviewSize) > 0 && !wouldAccept(strongSize, reviewSize);
  }

  /**
   * Scores a provider's raw proposals — this is the whole of the decision policy a {@code propose:}
   * rule gets, invariant 7's "a provider proposes, the hub decides" made concrete: an id absent from
   * the crosswalk is discarded before it is ever scored (a stale index must not mint a ghost entity);
   * duplicate proposals for the same entity (a provider returning it via two matching documents, say)
   * collapse to one candidate, never two; an asserted-distinct partner is excluded the same as an
   * {@code exact:} rule's own veto, and {@code vetoChangedOutcome} reports it the same way the
   * exact: rule's own inline check does — only when the veto actually crossed the accept/no-accept
   * or ambiguous/no-ambiguous line, not whenever a vetoed id merely happened to be present. What
   * remains is bucketed against the rule's own {@code acceptAbove}/{@code reviewAbove} — strong
   * candidates feed the ordinary accept/ambiguous size rule, a lone review-band candidate still
   * forces {@code ambiguous} (via #619's existing assertion mechanism, not a new review queue), and
   * anything weaker than {@code reviewAbove} is too noisy to be worth surfacing at all.
   */
  private CandidateTiers tierProposedCandidates(Connection c, long tenantId, String entityType,
      List<ProposedCandidate> proposed, EntityManifest.Propose propose, List<UUID> assertedDistinctEntityIds) {
    List<UUID> proposedIds = proposed.stream().map(ProposedCandidate::entityId).toList();
    Set<UUID> existing = repository.existingEntityIds(c, tenantId, entityType, proposedIds);

    List<ProposedCandidate> discarded = new ArrayList<>();
    Set<UUID> rawStrong = new LinkedHashSet<>();
    Set<UUID> rawReview = new LinkedHashSet<>();
    for (ProposedCandidate candidate : proposed) {
      if (!existing.contains(candidate.entityId())) {
        discarded.add(candidate);
      } else if (candidate.score() >= propose.acceptAbove()) {
        rawStrong.add(candidate.entityId());
      } else if (candidate.score() >= propose.reviewAbove()) {
        rawReview.add(candidate.entityId());
      }
    }
    // A duplicate proposal scored once strong and once review-band is the strong classification —
    // a weaker repeat of the same entity must not additionally flag it for review.
    rawReview.removeAll(rawStrong);

    List<UUID> strong = rawStrong.stream().filter(id -> !assertedDistinctEntityIds.contains(id)).toList();
    List<UUID> review = rawReview.stream().filter(id -> !assertedDistinctEntityIds.contains(id)).toList();

    boolean vetoChangedOutcome =
        (wouldAccept(rawStrong.size(), rawReview.size()) && !wouldAccept(strong.size(), review.size()))
        || (wouldBeAmbiguous(rawStrong.size(), rawReview.size()) && !wouldBeAmbiguous(strong.size(), review.size()));

    return new CandidateTiers(strong, review, discarded, vetoChangedOutcome);
  }

  /** {@code discarded_candidates} (V51) is a separate column, not a richer {@code candidates}
   * shape: {@link EntityIdentityRepository#lastResolvedEvidence} reads {@code candidates ->> 0} as
   * a bare UUID string for every {@code resolved} row, exact or propose alike, so {@code
   * candidates} itself must stay a flat UUID array for every rule type. */
  private String toJson(Object value, String label) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(label + ": expected always-serializable evidence data", e);
    }
  }
}

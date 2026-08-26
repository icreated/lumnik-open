package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConnectorDeadLetterRepository implements PanacheRepositoryBase<ConnectorDeadLetter, UUID> {

  private static final List<String> BENIGN_REASONS = List.of("Duplicate", "PolicyExcluded");

  public List<ConnectorDeadLetter> findPendingByConnector(UUID connectorId, int limit) {
    return find("connectorId = ?1 and resolution is null and deletedAt is null order by capturedAt desc", connectorId)
        .page(0, limit).list();
  }

  /**
   * Real-loss dead-letters for a run (excludes benign skips + soft-deleted).
   *
   * <p>OTK COUPLING — this benign exclusion is the conservation denominator consumed by
   * Law 1 in {@code lm/test/otk/checks/reconcile.go ReconcileCounts}. Law 1
   * ({@code recordsIn == recordsOut + dlqCount}) is correct only while benign reasons
   * (Duplicate/PolicyExcluded) never enter the recordsIn-not-recordsOut skip path
   * (i.e. are never dead-lettered). If that ever changes, Law 1 will false-positive on
   * benign skips — ReconcileCounts is the place that must be updated alongside this query.
   */
  public long countByRun(UUID runId) {
    return count("runId = ?1 and deletedAt is null and skipReason not in ?2", runId, BENIGN_REASONS);
  }

  /** All dead-letters a run produced, newest first (full transparency).
   *  deletedAt is null: GDPR-erased rows excluded by design (full transparency is over reasons, not erased rows). */
  public List<ConnectorDeadLetter> findByRun(UUID runId, int limit) {
    return find("runId = ?1 and deletedAt is null order by capturedAt desc", runId)
        .page(0, limit).list();
  }

  /** The unfiltered admin list, newest first, EXCLUDING soft-deleted/GDPR-erased rows — so the
   *  no-filter list endpoint can't leak erased payloads (consistent with findByRun / GET /{id}). */
  public List<ConnectorDeadLetter> findAllActive(int limit) {
    return find("deletedAt is null order by capturedAt desc")
        .page(0, limit).list();
  }
}

package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import io.lumnik.hub.connector.api.types.SkipReason;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConnectorDeadLetterRepository implements PanacheRepositoryBase<ConnectorDeadLetter, UUID> {

  public List<ConnectorDeadLetter> findPendingByConnector(UUID connectorId, int limit) {
    return find("connectorId = ?1 and resolution is null and deletedAt is null order by capturedAt desc", connectorId)
        .page(0, limit).list();
  }

  /**
   * Real-loss dead-letters for a run (excludes benign skips + soft-deleted).
   *
   * <p>The excluded set is {@link SkipReason#BENIGN_NAMES}, not a local copy: this query is
   * string-keyed against the persisted name while the runtime checks are type-keyed, and those
   * two spellings of one rule used to be kept in step by a comment. See that constant for the
   * OTK coupling — Law 1's conservation denominator is this exclusion, and its fourth reader
   * is in Go.
   */
  public long countByRun(UUID runId) {
    return count("runId = ?1 and deletedAt is null and skipReason not in ?2",
        runId, SkipReason.BENIGN_NAMES);
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

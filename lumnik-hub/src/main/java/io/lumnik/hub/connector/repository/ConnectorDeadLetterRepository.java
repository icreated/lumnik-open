package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import io.lumnik.hub.connector.api.types.SkipReason;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ConnectorDeadLetterRepository implements PanacheRepositoryBase<ConnectorDeadLetter, UUID> {

  public List<ConnectorDeadLetter> findPendingByConnector(UUID connectorId, int limit) {
    return find("connectorId = ?1 and resolution is null and deletedAt is null order by capturedAt desc", connectorId)
        .page(0, limit).list();
  }

  /** Real-loss dead-letters for one run — {@link #countByRuns} of a single id. */
  public long countByRun(UUID runId) {
    return countByRuns(List.of(runId)).get(runId);
  }

  /**
   * Real-loss dead-letters per run (excludes benign skips + soft-deleted), in ONE grouped query:
   * the runs ledger used to ask once per run of its page, up to 200 times (#892). Every id asked
   * is a key — 0 for a run with no real loss — so no caller has to read an absent key as zero.
   *
   * <p>The excluded set is {@link SkipReason#BENIGN_NAMES}, not a local copy: this query is
   * string-keyed against the persisted name while the runtime checks are type-keyed, and those
   * two spellings of one rule used to be kept in step by a comment. See that constant for the
   * OTK coupling — Law 1's conservation denominator is this exclusion, and its fourth reader
   * is in Go.
   */
  public Map<UUID, Long> countByRuns(Collection<UUID> runIds) {
    var counts = new HashMap<UUID, Long>();
    if (runIds.isEmpty()) return counts;
    runIds.forEach(id -> counts.put(id, 0L));
    getEntityManager()
        .createQuery("select d.runId, count(d) from ConnectorDeadLetter d where d.runId in :runIds"
            + " and d.deletedAt is null and d.skipReason not in :benign group by d.runId", Object[].class)
        .setParameter("runIds", runIds)
        .setParameter("benign", SkipReason.BENIGN_NAMES)
        .getResultStream()
        .forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
    return counts;
  }

  /** All dead-letters a run produced, newest first (full transparency).
   *  deletedAt is null: GDPR-erased rows excluded by design (full transparency is over reasons, not erased rows). */
  public List<ConnectorDeadLetter> findByRun(UUID runId, int limit) {
    return find("runId = ?1 and deletedAt is null order by capturedAt desc", runId)
        .page(0, limit).list();
  }

  /** The unfiltered admin list, newest first, EXCLUDING soft-deleted/GDPR-erased rows — so the
   *  no-filter list endpoint can't leak erased payloads (consistent with findByRun / GET /{id}).
   *  {@code heldConnectors} narrows it to a scope-bound caller's reach (#1212); null for an unbound
   *  one, and empty holds nothing. */
  public List<ConnectorDeadLetter> findAllActive(Collection<UUID> heldConnectors, int limit) {
    if (heldConnectors != null && heldConnectors.isEmpty()) return List.of();
    return (heldConnectors == null
        ? find("deletedAt is null order by capturedAt desc")
        : find("connectorId in ?1 and deletedAt is null order by capturedAt desc", heldConnectors))
        .page(0, limit).list();
  }
}

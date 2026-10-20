package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.ConnectorRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ConnectorRunRepository implements PanacheRepositoryBase<ConnectorRun, UUID> {
  /**
   * A connector's runs, newest first, ACTIVE ones only — the by-connector views, which have always
   * hidden soft-deleted runs. The policy is in the name because the ledger below applies the
   * opposite one over nearly the same query: picking the wrong twin silently changes the SET, not
   * the shape, and nothing downstream can tell.
   */
  public List<ConnectorRun> findRecentActiveByConnector(UUID connectorId, int limit) {
    return find("connectorId = ?1 and deletedAt is null order by startedAt desc", connectorId)
        .page(0, limit).list();
  }

  /** The newest ACTIVE run of a connector — same policy, and same reason to say so, as its sibling above. */
  public Optional<ConnectorRun> findLatestActiveByConnector(UUID connectorId) {
    return find("connectorId = ?1 and deletedAt is null order by startedAt desc", connectorId)
        .firstResultOptional();
  }

  /**
   * The cross-connector ledger, newest first, narrowed by whichever ids are given — a null one
   * means "every". Both together narrow twice rather than one winning: an endpoint belongs to a
   * connector, so naming both either agrees with itself or is a question with no answer, and
   * inventing a precedence would hide the second case.
   *
   * <p>No {@code deletedAt} filter: the ledger has always shown soft-deleted runs, and narrowing
   * must RESTRICT it, never redefine it. Deliberately NOT {@link #findRecentActiveByConnector} —
   * wiring the filter to that predicate would make a run the reader sees unfiltered vanish the
   * moment they narrow to its connector, which is the silence these filters exist to end.
   *
   * <p>{@code tenantId} is the query's own predicate, as in {@link #findByIdForTenant}: the
   * {@code @Filter} is off on a {@code @Transactional} session (see IntegrationTenantPredicateIT),
   * so the page relied on RLS alone — whose policy no index can serve — and scanned and sorted the
   * run history on every refresh (#892). With it, {@code idx_run_tenant_started} hands over the
   * newest runs.
   *
   * <p>{@code heldConnectors} is the scope-bound caller's reach (#1212), null for an unbound one:
   * it narrows the query like the two ids, so the page is the caller's newest runs. Empty holds
   * nothing.
   */
  public List<ConnectorRun> findRecent(Long tenantId, UUID connectorId, UUID endpointId,
                                       Collection<UUID> heldConnectors, int limit) {
    var clauses = new ArrayList<String>();
    var params = new HashMap<String, Object>();
    clauses.add("tenantId = :tenantId");
    params.put("tenantId", tenantId);
    if (connectorId != null) {
      clauses.add("connectorId = :connectorId");
      params.put("connectorId", connectorId);
    }
    if (endpointId != null) {
      clauses.add("endpointId = :endpointId");
      params.put("endpointId", endpointId);
    }
    if (heldConnectors != null && heldConnectors.isEmpty()) return List.of();
    if (heldConnectors != null) {
      clauses.add("connectorId in :held");
      params.put("held", heldConnectors);
    }
    return find(String.join(" and ", clauses) + " order by startedAt desc", params).page(0, limit).list();
  }

  /**
   * Looks up a run by id, scoped to {@code tenantId} — the tenant boundary for every
   * platform-admin read of a single run (get, cancel, and cancel's post-race re-read).
   * {@code EntityManager.find()}/Panache's id-only lookup bypasses the {@code @Filter} that
   * enforces tenancy elsewhere, so this explicit predicate is the guard; a run belonging to
   * another tenant is indistinguishable from a missing one. Returns {@code null} when no run
   * matches, matching {@code firstResult()} at the call sites.
   */
  public ConnectorRun findByIdForTenant(UUID id, Long tenantId) {
    return find("id = ?1 and tenantId = ?2", id, tenantId).firstResult();
  }
}

package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class DeadLetterCapture {

  @Inject ConnectorDeadLetterRepository repo;
  @Inject ConnectorRepository connectors;
  @Inject ObjectMapper mapper;
  @Inject IngestionMetrics metrics;

  @Transactional
  public UUID capture(UUID connectorId, UUID runId, JsonNode payload, SkipReason reason) {
    ConnectorDeadLetter dlq = new ConnectorDeadLetter();
    dlq.setTenantId(resolveTenantId(connectorId));
    dlq.setConnectorId(connectorId);
    dlq.setRunId(runId);
    dlq.setPayload(payload);
    dlq.setSkipReason(reason.persistedName());
    dlq.setSkipDetail(mapper.valueToTree(reason));
    dlq.setErrorMessage(reasonMessage(reason));
    repo.persist(dlq);
    // Count real losses only, aligning the metric with deadLetterCount/countByRun: benign skips
    // (Duplicate/PolicyExcluded) are not a loss. capture() itself accepts any reason (tests do exercise
    // benign ones), but the ingestion RUNTIME never routes a benign skip here today (BenignSkipNoDlqIT:
    // duplicates are skipped, not dead-lettered) — this guard keeps lumnik_dead_letters_total correct
    // even if a future path ever dead-letters a benign skip.
    if (!reason.benign()) {
      metrics.recordDeadLetter(dlq.getSkipReason());
    }
    return dlq.getId();
  }

  @Transactional
  public void discard(UUID dlqId, long userId) {
    var dlq = repo.findById(dlqId);
    if (dlq == null) return;
    dlq.setResolution("DISCARDED");
    dlq.setResolvedAt(Instant.now());
    dlq.setResolvedBy(userId);
  }

  @Transactional
  public void markReplayed(UUID dlqId, UUID newRunId, long userId) {
    var dlq = repo.findById(dlqId);
    if (dlq == null) return;
    dlq.setResolution("REPLAYED");
    dlq.setResolvedAt(Instant.now());
    dlq.setResolvedBy(userId);
    dlq.setResolvedRunId(newRunId);
  }

  /**
   * Resolves tenant ID from the thread-local context when available (normal request path),
   * or falls back to looking up the connector's own tenantId (system / scheduler mode).
   */
  private Long resolveTenantId(UUID connectorId) {
    Long fromContext = CurrentTenantHolder.get();
    if (fromContext != null) return fromContext;
    var connector = connectors.findById(connectorId);
    if (connector == null) throw new IllegalArgumentException("Connector not found: " + connectorId);
    return connector.getTenantId();
  }

  private String reasonMessage(SkipReason r) {
    return switch (r) {
      case SkipReason.MappingFailed m    -> m.field() + ": " + m.message();
      case SkipReason.ValidationFailed v -> String.join("; ", v.violations());
      case SkipReason.WriteFailed w      -> w.entity() + ": " + w.cause();
      case SkipReason.Duplicate d        -> "duplicate key " + d.businessKey();
      case SkipReason.PolicyExcluded p   -> "excluded by policy " + p.policyName();
      case SkipReason.MalformedSource m  -> m.file() + ":" + m.line() + " — " + m.message();
    };
  }
}

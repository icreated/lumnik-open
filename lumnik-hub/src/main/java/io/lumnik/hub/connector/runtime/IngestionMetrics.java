package io.lumnik.hub.connector.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Domain counters for ingestion observability (thin floor — no dashboards).
 *
 * <p>Best-effort, at-least-once semantics: increments happen inside the caller's
 * {@code @Transactional} boundary, so a rolled-back run/capture may still have
 * incremented its counter (a small over-count on rare failure paths). The
 * authoritative honesty signal is the DB-derived {@code deadLetterCount}, not
 * these counters — so a post-commit hook would be machinery this floor doesn't need.
 */
@ApplicationScoped
public class IngestionMetrics {

  @Inject MeterRegistry registry;

  /** A run reached a terminal status. */
  public void recordRunFinished(String status) {
    registry.counter("lumnik_runs_total", "status", status).increment();
  }

  /** A row was quarantined to the dead-letter store. */
  public void recordDeadLetter(String reason) {
    registry.counter("lumnik_dead_letters_total", "reason", reason).increment();
  }
}

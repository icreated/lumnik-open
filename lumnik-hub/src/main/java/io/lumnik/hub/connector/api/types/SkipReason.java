package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface SkipReason {
  record MappingFailed(String field, String message)           implements SkipReason {}
  record ValidationFailed(List<String> violations)             implements SkipReason {}
  record WriteFailed(String entity, String cause)              implements SkipReason {}
  record Duplicate(String businessKey)                         implements SkipReason {}
  record PolicyExcluded(String policyName)                     implements SkipReason {}
  record MalformedSource(String file, long line, String message) implements SkipReason {}

  /**
   * The names of the skips that are DECLARED rather than lost — the row was not dropped by a
   * failure, it was excluded on purpose (already present, or filtered by a declared policy).
   *
   * <p>THE one home. This predicate had three: {@code ConnectorDeadLetterRepository.BENIGN_REASONS}
   * keyed by the persisted string, {@code ChunkExecutor.outcomeSkipped} keyed by type, and
   * {@code DeadLetterCapture.capture}'s metric exemption keyed by type again — under a comment
   * asking the next reader to keep them in sync by hand. Three copies of one rule is three chances
   * to add a fourth reason to two of them.
   *
   * <p>⚠️ A FOURTH reader lives in Go and cannot import this: OTK Law 1
   * ({@code recordsIn == recordsOut + dlqCount}, {@code lm/test/otk/checks/reconcile.go}) is
   * correct only while a benign skip is never dead-lettered. Adding a reason here means editing
   * that file too — {@code SkipReasonInventoryTest} names it in the assertion that fails.
   */
  List<String> BENIGN_NAMES = List.of("Duplicate", "PolicyExcluded");

  /**
   * The name this reason persists under in {@code connector_dead_letter.skip_reason} — the type's
   * own business, and what lets the string-keyed query and the type-keyed checks share one rule.
   * Exhaustive over the sealed hierarchy, so a new variant is a COMPILE error here rather than a
   * silent {@code null} downstream.
   */
  default String persistedName() {
    return switch (this) {
      case MappingFailed m    -> "MappingFailed";
      case ValidationFailed v -> "ValidationFailed";
      case WriteFailed w      -> "WriteFailed";
      case Duplicate d        -> "Duplicate";
      case PolicyExcluded p   -> "PolicyExcluded";
      case MalformedSource m  -> "MalformedSource";
    };
  }

  /** A declared skip, not a loss — see {@link #BENIGN_NAMES}. */
  default boolean benign() {
    return BENIGN_NAMES.contains(persistedName());
  }
}

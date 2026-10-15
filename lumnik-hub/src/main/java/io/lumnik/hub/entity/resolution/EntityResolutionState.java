package io.lumnik.hub.entity.resolution;

import java.util.Locale;

/**
 * The four outcomes of resolving one source row against the cascade (design doc §7). Names match
 * {@code core.entity_identity.state} / {@code core.entity_resolution_evidence.outcome} lowercased —
 * {@link #sqlValue()} is the single conversion point so the two never drift apart.
 */
public enum EntityResolutionState {
  /** At least one rule produced a key: either an existing candidate matched, or a new entity is minted. */
  RESOLVED,
  /** Two or more distinct candidate entities, and no later rule arbitrated. Never a merge. */
  AMBIGUOUS,
  /** No rule in the cascade could produce a key at all — the row carries no usable identity data. */
  UNRESOLVED_NO_RULE,
  /** A {@code propose:} rule was reached and no candidate provider is available to answer it. */
  UNRESOLVED_PROVIDER;

  public String sqlValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The inverse of {@link #sqlValue()} — reading a {@code state}/{@code outcome} column back. */
  public static EntityResolutionState fromSql(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}

package io.lumnik.hub.entity.resolution;

/**
 * Thrown by {@link NormalizationOrchestrator} for a {@code normalize:} hook id that is not in
 * {@link NormalizerAllowlist#ADMITTED} — including one unknown to {@code HookCatalog} entirely.
 * {@code EntityManifestParser} already refuses this at apply time; this is the same refusal
 * enforced again where the orchestrator actually runs a chain, so a manifest stored before the
 * allowlist tightened cannot bypass it.
 */
public class NormalizerNotAdmittedException extends RuntimeException {

  public NormalizerNotAdmittedException(String hookId) {
    super("normalizer '" + hookId + "' is not admitted by NormalizerAllowlist");
  }
}

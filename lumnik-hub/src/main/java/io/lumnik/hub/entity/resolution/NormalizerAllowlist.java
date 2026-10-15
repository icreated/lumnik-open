package io.lumnik.hub.entity.resolution;

import java.util.Map;
import java.util.Set;

/**
 * Versioned registry of {@code CellHook} ids a {@code resolution:} cascade rule's
 * {@code normalize:} may name (design doc §5). An entry serves two jobs at once: it says which
 * hooks may build an identity key, and its version is what {@link #versionOf} folds into the rule
 * identity (design doc §6) — a hook cannot be admitted without one.
 *
 * <p>The exclusion criterion is not "impure": {@code lookup}'s table comes from the manifest's own
 * params, no {@code DataSource} involved. The criterion is that a normalizer may collapse two
 * distinct values into one only when that collapse <b>is</b> the intended equivalence. {@code
 * lower} collapses {@code Alice}/{@code alice} — intended. {@code mask}, {@code default}, {@code
 * lookup} (with {@code on-missing: <literal>}), {@code truncate} and {@code split} each collapse
 * values that were never meant to be equivalent; {@code kotlin} has no fixed semantics to pin.
 *
 * <p><b>Lot #616 settled the provisional "if pinned" half of the design doc's table, by property
 * test rather than by reading the source</b> ({@code NormalizationOrchestratorPropertyTest}). The
 * result moved seven hooks from admitted to excluded, not because their semantics are dangerous in
 * general, but because {@code normalize:} names a hook by id alone — no per-hook params reach it —
 * and every one of them needs params to do anything other than collapse every row that cannot be
 * read as its target shape onto the same constant:
 * <ul>
 *   <li>{@code parse-phone}, {@code parse-email}, {@code parse-number}, {@code to-uuid} — each
 *       falls back to {@code null} on anything it cannot parse, so two unrelated malformed values
 *       (an empty string and a stray comment) fingerprint identically. That is exactly the "a
 *       failure never produces a constant" property #616 tests for, and it fails it against
 *       uncontrolled input.</li>
 *   <li>{@code parse-date} — {@code formats} is mandatory for it to parse anything; with no way to
 *       supply it via a bare {@code normalize:} id, it nulls <em>every</em> row, pinned or not.</li>
 *   <li>{@code regex-replace}, {@code regex-extract} — both need a {@code pattern} from params
 *       that {@code normalize:} cannot supply either; unconfigured, {@code regex-replace} is a
 *       silent no-op and {@code regex-extract} writes to columns {@code normalize:} has no way to
 *       name, which is the same "changes the row's shape instead of normalizing a scalar" reason
 *       {@code split} is already excluded for.</li>
 * </ul>
 * Reinstating any of them is a manifest-vocabulary change (per-hook params on a
 * {@code normalize:} entry, not just an id) plus its own property test — not a one-line allowlist
 * edit.
 *
 * <p>The nine still admitted here take no params beyond the column they run on, so
 * {@link io.lumnik.hub.entity.resolution.NormalizationOrchestrator} can synthesize exactly that
 * and every one of them holds the "only the intended equivalence collapses" property on arbitrary
 * text.
 */
public final class NormalizerAllowlist {

  private NormalizerAllowlist() {}

  /** Admitted hook id -&gt; semantic version of its normalization behaviour. */
  public static final Map<String, String> ADMITTED = Map.ofEntries(
      Map.entry("lower", "1.0.0"),
      Map.entry("upper", "1.0.0"),
      Map.entry("clean", "1.0.0"),
      Map.entry("remove-diacritics", "1.0.0"),
      Map.entry("strip-invisible", "1.0.0"),
      Map.entry("fix-mojibake", "1.0.0"),
      Map.entry("replace-smart-quotes", "1.0.0"),
      Map.entry("decode-html-entities", "1.0.0"),
      Map.entry("titlecase", "1.0.0"));

  /** Excluded hook ids — see the class doc for the criterion. */
  public static final Set<String> EXCLUDED = Set.of(
      "mask", "default", "lookup", "truncate", "split", "kotlin",
      "parse-phone", "parse-date", "parse-email", "to-uuid", "regex-replace", "regex-extract",
      "parse-number");

  public static boolean isAdmitted(String hookId) {
    return ADMITTED.containsKey(hookId);
  }

  public static String versionOf(String hookId) {
    return ADMITTED.get(hookId);
  }
}

package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.RegexParams;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a pattern's capture groups into named columns.
 *
 * <p>Everything {@code groups} can get wrong is knowable from the manifest alone — the pattern is
 * in it, so its capture-group count is too — and all of it is refused by name before a cell is
 * read. It used to be swallowed instead, in two different ways, and one of them was fatal: a
 * NEGATIVE key reached {@code Matcher.group(-1)}, whose {@code IndexOutOfBoundsException} was not
 * one of the two the hook caught, so it escaped and took the whole chunk with it.
 *
 * <p>All of it is refused in {@code validateParams} — once per spec, before any row. It used to be
 * split across {@code apply}: key-shape ran on every row (empty cell included, so an all-null
 * column could not smuggle a bad manifest past it) and the range check was deferred to the first
 * non-null row to avoid a per-row pattern compile. The seam collapses both into one VALIDATION
 * compile per spec that refuses even an empty chunk. ({@code apply} still compiles the pattern per
 * non-null cell to MATCH — caching that is the separate Pattern-cache lot, not this one.)
 *
 * <p>A pattern that does not MATCH is data, not a manifest error, and it is <b>not counted</b>.
 * That looks inconsistent with {@code split}'s shortfall until you ask what makes a decline
 * exceptional: {@code to-uuid} and {@code fix-mojibake} need a BROKEN value, so their counts stay
 * rare. A regex miss is a property of a heterogeneous column, not of a bad cell — an optional
 * field misses on every row — which puts it in {@code lookup}'s family, and {@code lookup} is
 * quiet by default for the same reason. {@code cell_errors} is ONE scalar for the whole run,
 * shared by every hook in the mapping: a hook that counts on a normal condition does not merely
 * misreport itself, it buries the {@code parse-date} failure sitting beside it.
 *
 * <p>Unless the integrator says otherwise: {@code required: true} (#1417), like {@code lookup}'s {@code on-missing},
 * declares that this column has no optional rows, and a miss on a non-empty cell then counts.
 *
 * <p>What the operator gets instead is structural: every column {@code groups} names exists on
 * every row — matched, missed, or never attempted because the cell was empty — so an all-null
 * column is the signal, and the table's shape never depends on which row arrived first.
 */
@ApplicationScoped
@HookId("regex-extract")
public class RegexExtractHook implements CellHook {

  /**
   * Last (pattern source, compiled Pattern) seen — a single-slot cache so a run's rows do not each
   * recompile the same regex. ONE immutable carrier in a {@code volatile} field, compared by source
   * string: within a run (one pattern) every row after the first reuses it; a concurrent run with a
   * different pattern simply overwrites the slot and recompiles its own — correct always, no growth.
   * (A per-pattern map on this app-scoped singleton would leak across every distinct pattern ever
   * seen; the single slot cannot.)
   */
  private volatile Compiled cache;

  private record Compiled(String source, Pattern pattern) {}

  @Override
  public void validateParams(JsonNode params) {
    // Everything `groups` can get wrong is knowable from the manifest — the pattern is in it, so
    // its capture-group count is too — and validateParams runs ONCE per spec, so both checks (keys
    // are non-negative numbers, and each is within the pattern's group count) run here rather than
    // per row. That retires the old split where key-shape ran on every row (empty cell included, so
    // an all-null column could not smuggle a bad manifest past it) and the range check was deferred
    // to the first non-null row to avoid a per-row compile: compiling once here costs nothing per row.
    // `required` is a boolean or absent — asBoolean() would read "yes" as false and quietly mean the default.
    ScalarParams.optionalBoolean("regex-extract hook", "required", params.path("required"), false);
    String patternStr = RegexParams.patternText("regex-extract", "pattern", params.path("pattern"));
    if (patternStr.isEmpty()) return;
    JsonNode groups = params.path("groups");
    if (groups.isMissingNode() || groups.isNull()) return;   // nothing declared to extract into
    requireKeysAreNumbers(groups);
    requireKeysAreWithinRangeOf(groups, compile(patternStr));
    // #1505: a miss writes null — over the column it reads, that erases the value read.
    String read = params.path("column").asText();
    groups.fields().forEachRemaining(g -> {
      if (g.getValue().asText().equals(read)) {
        throw new MappingValidationException("regex-extract hook: group " + g.getKey() + " writes '" + read
            + "', the column it reads — a miss would write null over it. Write the group to a column of its own; "
            + "to rewrite a value in place, use regex-replace");
      }
    });
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    // Read the pattern the same way validateParams did: a bare `pattern:` (null) is absent.
    String patternStr = RegexParams.patternText("regex-extract", "pattern", params.path("pattern"));
    if (patternStr.isEmpty()) return;

    JsonNode groups = params.path("groups");
    if (groups.isMissingNode() || groups.isNull()) return;   // nothing declared to extract into
    refuseColumnsAlreadyInTheRow(row, groups);

    Object v = row.get(params.path("column").asText());
    if (v == null) {
      // No cell, no extraction — and the answer does not depend on the pattern, so do not compile
      // one. Measured: compiling here cost 119 ns on EVERY null row of a sparse column, 17x the
      // whole hook, for a result that is null either way. The declared columns still appear.
      groups.fields().forEachRemaining(e -> row.put(e.getValue().asText(), null));
      return;
    }

    Pattern pattern = compile(patternStr);

    Matcher matched = null;
    {
      Matcher m = pattern.matcher(v.toString());
      // Full match preferred over a find, and neither shortcut is equivalent: find() alone differs
      // on a reluctant quantifier, and wrapping the pattern as ^(?:p)$ differs on a trailing
      // newline, which Java's $ admits. reset() returns this, so the two attempts are one line.
      if (m.matches() || m.reset().find()) matched = m;
    }

    // Walk the DECLARED groups, not what matched — SplitHook's rule. This is the third and last
    // route to the same promise: matched here, missed here, and an empty cell handled above.
    // Validated above, so parseInt cannot fail and group(idx) cannot be out of range.
    // #1417: quiet by default (above); `required: true` is the integrator saying this column has no optional rows,
    // so a non-empty cell the pattern cannot read is a cell not delivered — and on-error decides its row.
    if (matched == null && params.path("required").asBoolean(false)) {
      s.counters().cellErrors().incrementAndGet();
    }
    Matcher result = matched;
    groups.fields().forEachRemaining(e -> row.put(e.getValue().asText(),
        result == null ? null : result.group(Integer.parseInt(e.getKey()))));
  }

  /**
   * #1505: a group writes its column on every row, null where the pattern misses — over a column the row already
   * holds (the file's, its own {@code column}, an earlier hook's) that would erase a value and count nothing. The
   * validator refuses it when the columns are known; with a header read at run time, the run fails here, by name.
   */
  private static void refuseColumnsAlreadyInTheRow(Map<String, Object> row, JsonNode groups) {
    for (JsonNode column : groups) {
      if (row.containsKey(column.asText())) {
        throw new MappingValidationException("regex-extract: column '" + column.asText() + "' is already in the row "
            + "— the file or an earlier hook writes it; this hook would write null over it on every row it does not "
            + "match. Write the group to a column of its own");
      }
    }
  }

  private Pattern compile(String patternStr) {
    Compiled c = cache;   // one volatile read
    if (c != null && c.source().equals(patternStr)) return c.pattern();
    Pattern p = RegexParams.compileOrRefuse("regex-extract", "pattern", patternStr);
    cache = new Compiled(patternStr, p);
    return p;
  }

  /**
   * Everything a key can get wrong that does NOT need the pattern. Covers the key error that once
   * aborted the chunk (a negative index reaching {@code Matcher.group(-1)}). Called from
   * {@code validateParams}, so it runs once per spec — an all-null column can no longer smuggle a
   * bad manifest past it, since the refusal no longer depends on a row arriving at all.
   */
  private static void requireKeysAreNumbers(JsonNode groups) {
    if (!groups.isObject()) {
      throw new MappingValidationException(
          "regex-extract hook: 'groups' must be a map of capture-group number to column name "
          + "(got " + MappingValidationException.typeName(groups) + ") — for example "
          + "groups: { \"1\": year }");
    }
    for (var entries = groups.fields(); entries.hasNext(); ) {
      var entry = entries.next();
      String key = entry.getKey();
      // The value names the column the group lands in: a list would name the column "", a null the column "null".
      ScalarParams.text("regex-extract hook", "groups." + key, entry.getValue());
      int idx;
      try {
        idx = Integer.parseInt(key);
      } catch (NumberFormatException notANumber) {
        throw new MappingValidationException(
            "regex-extract hook: 'groups' key '" + key + "' must be a capture-group number "
            + "(0 is the whole match, 1 is the first group)");
      }
      if (idx < 0) {
        throw new MappingValidationException(
            "regex-extract hook: 'groups' key '" + idx + "' must not be negative "
            + "(0 is the whole match, 1 is the first group)");
      }
    }
  }

  /**
   * The check that needs the pattern's group count. Once deferred to the first non-null row to
   * avoid a per-row compile; now that {@code validateParams} runs once per spec, it compiles a
   * single time here and the deferral is gone.
   */
  private static void requireKeysAreWithinRangeOf(JsonNode groups, Pattern pattern) {
    // groupCount is a property of the pattern; the empty matcher is only how Java exposes it.
    // Pattern has no public accessor (capturingGroupCount is package-private) and counting '('
    // by hand is WRONG: a named group (?<year>...) captures but starts with "(?", so a naive
    // scan reports 0 and would refuse a valid manifest.
    int available = pattern.matcher("").groupCount();
    for (var entries = groups.fields(); entries.hasNext(); ) {
      // Numeric and non-negative already, from requireKeysAreNumbers in the same validateParams.
      int idx = Integer.parseInt(entries.next().getKey());
      if (idx > available) {
        throw new MappingValidationException(
            "regex-extract hook: 'groups' key '" + idx + "' is not a capture group of this "
            + "pattern, which has " + available + " (0 is the whole match)");
      }
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "pattern", "groups", "required");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    ColumnEffect.Builder b = ColumnEffect.builder().reads("column", params.path("column"));
    params.path("groups").forEach(b::adds);
    return b.build();
  }
}

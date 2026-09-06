package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingValidationException;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared regex-param handling for the hooks that take a manifest-supplied pattern. Compile it or
 * refuse it in the house's typed way — the same idiom {@code MappingValidationException.typeName}
 * centralises for node types. The wording had copied itself across {@code regex-extract},
 * {@code regex-replace} and {@code filter-where}; one owner, the hook name AND the knob name both
 * parameters (the knob is {@code pattern} for the regex hooks but {@code regex} for filter-where).
 * Lives in the parent {@code hooks} package (not {@code hooks.cell}) so a dataset hook can share it.
 */
public final class RegexParams {

  private RegexParams() {}

  /**
   * The pattern node as its regex string. A container node ({@code {…}} / {@code [...]}) is a
   * manifest mistake, not an empty pattern: {@code asText()} would flatten it to {@code ""} and the
   * hook would silently no-op. Refuse it here — a scalar is read as text; a bare/absent node yields
   * {@code ""}, which callers treat as "no pattern, skip".
   */
  public static String patternText(String hook, String knob, JsonNode node) {
    // A bare `knob:` (null) or an absent one reads as no pattern — the house convention, and NOT the
    // literal string "null" that NullNode.asText() would hand back.
    if (node.isMissingNode() || node.isNull()) return "";
    if (node.isContainerNode()) {
      throw new MappingValidationException(
          hook + " hook: '" + knob + "' must be a regex string (got "
          + MappingValidationException.typeName(node) + ")");
    }
    return node.asText();
  }

  /** The compiled pattern, or a {@link MappingValidationException} naming the hook and the knob. */
  public static Pattern compileOrRefuse(String hook, String knob, String pattern) {
    try {
      return Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw new MappingValidationException(
          hook + " hook: '" + knob + "' is not a valid regex: " + e.getDescription(), e);
    }
  }
}

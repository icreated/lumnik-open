package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A constant a manifest hands a hook to write into a column — an {@code enrich-context} or {@code defaults} value,
 * a column name — is text. {@code asText()} reads a number or a boolean as its text, and that form is kept; but it
 * reads a list or a map as {@code ""} and a null as the word {@code "null"}, in silence (#1441). Those are refused,
 * naming the owner and the key. Beside {@code MappingValidationException.typeName}, so the manifest loader and
 * the hooks share it.
 */
public final class ScalarParams {

  private ScalarParams() {}

  /** The value's text form, or a {@link MappingValidationException} naming {@code owner} and {@code key}. */
  public static String text(String owner, String key, JsonNode value) {
    if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
      throw new MappingValidationException(owner + ": '" + key + "' must be text, a number or a boolean (got "
          + MappingValidationException.typeName(value) + ")");
    }
    return value.asText();
  }

  /** {@link #text}, or {@code absent} when the key is missing or bare ({@code key:} is null): an optional knob. */
  public static String optionalText(String owner, String key, JsonNode value, String absent) {
    return value.isMissingNode() || value.isNull() ? absent : text(owner, key, value);
  }

  /**
   * A YAML boolean, or {@code absent} when the key is missing or bare: an optional switch (#1486). Anything else —
   * the text {@code "true"} included, as for every other boolean a manifest declares — is refused by its shape:
   * {@code asBoolean(false)} read it as false, in silence.
   */
  public static boolean optionalBoolean(String owner, String key, JsonNode value, boolean absent) {
    if (value.isMissingNode() || value.isNull()) return absent;
    if (!value.isBoolean()) {
      throw new MappingValidationException(owner + ": '" + key + "' must be true or false (got "
          + MappingValidationException.typeName(value) + ")");
    }
    return value.booleanValue();
  }

  /** {@link #text} that names a column: empty or blank text names none, and is refused like a bare key. */
  public static String columnName(String owner, String key, JsonNode value) {
    String name = text(owner, key, value);
    if (name.isBlank()) throw blank(owner, key);
    return name;
  }

  /**
   * A list's elements as {@link #text}, element {@code i} named {@code key[i]}; anything else is refused (#1465), and
   * so is an empty list: a hook configured with one does nothing, or — clean's {@code columns} — the opposite of the
   * key left out.
   */
  public static List<String> textList(String owner, String key, JsonNode value) {
    if (!value.isArray()) {
      throw new MappingValidationException(owner + ": '" + key + "' must be a list of text, numbers or booleans (got "
          + MappingValidationException.typeName(value) + ")");
    }
    if (value.isEmpty()) {
      throw new MappingValidationException(owner + ": '" + key + "' must list at least one value (got an empty list)");
    }
    List<String> texts = new ArrayList<>(value.size());
    for (int i = 0; i < value.size(); i++) texts.add(text(owner, key + "[" + i + "]", value.get(i)));
    return texts;
  }

  /**
   * {@link #textList} whose elements name columns: each one a {@link #columnName}, so a blank element is refused like
   * an empty list (#1482). Not for a list of values to match — an empty string is a value a cell can hold.
   */
  public static List<String> columnNames(String owner, String key, JsonNode value) {
    List<String> names = textList(owner, key, value);
    for (int i = 0; i < names.size(); i++) {
      if (names.get(i).isBlank()) throw blank(owner, key + "[" + i + "]");
    }
    return names;
  }

  /**
   * {@link #optionalText} that must be one of {@code allowed}, matched as written; {@code absent} when missing or bare.
   * The refusal names the words, never the value.
   */
  public static String optionalWord(String owner, String key, JsonNode value, String absent, List<String> allowed) {
    String word = optionalText(owner, key, value, absent);
    if (word != null && !allowed.contains(word)) {
      List<String> quoted = allowed.stream().map(w -> "\"" + w + "\"").toList();
      throw new MappingValidationException(owner + ": '" + key + "' must be "
          + String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.get(quoted.size() - 1));
    }
    return word;
  }

  private static MappingValidationException blank(String owner, String key) {
    return new MappingValidationException(owner + ": '" + key + "' must name a column (got blank text)");
  }

  /**
   * {@link #optionalText} for a separator: empty text is refused, since splitting on it cuts a value into its
   * characters (#1485). Blank text is kept — a space is a separator.
   */
  public static String optionalSeparator(String owner, String key, JsonNode value, String absent) {
    String sep = optionalText(owner, key, value, absent);
    if (sep != null && sep.isEmpty()) {
      throw new MappingValidationException(owner + ": '" + key + "' must not be empty — an empty separator splits a "
          + "value into its characters");
    }
    return sep;
  }
}

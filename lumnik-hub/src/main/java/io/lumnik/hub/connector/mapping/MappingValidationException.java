package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

public class MappingValidationException extends RuntimeException {
  public MappingValidationException(String message) { super(message); }
  public MappingValidationException(String message, Throwable cause) { super(message, cause); }

  /**
   * How a node's SHAPE is named in a refusal: "object", "array", "string". Written once because
   * five refusals had copied {@code getNodeType().toString().toLowerCase()} — and every copy
   * folded case against the JVM default, on names that contain {@code I} (STRING, MISSING,
   * BINARY). Under a Turkish default those messages corrupt themselves, which is the same defect
   * the case hooks were just pinned against.
   */
  public static String typeName(JsonNode node) {
    return node.getNodeType().toString().toLowerCase(Locale.ROOT);
  }
}

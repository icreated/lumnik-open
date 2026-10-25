package io.lumnik.hub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SHA-256 of a value's canonical JSON — maps sorted by key at every depth (jsonb drops key order), list order kept:
 * one identity for "the same definition", whatever order its keys were written in. A run's definition witness
 * ({@code DefinitionHash}) and a carry's mapping fingerprint (#1452) both hash through it.
 */
public final class CanonicalHash {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CanonicalHash() {}

  public static String of(Object value) {
    try {
      return AuditRecorder.sha256(MAPPER.writeValueAsString(canonical(MAPPER.convertValue(value, Object.class))));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a definition could not be hashed", e);
    }
  }

  private static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(CanonicalHash::canonical).toList();
    return value;
  }
}

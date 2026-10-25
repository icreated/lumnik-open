package io.lumnik.hub.connector.mapping.carry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The carry's storage form (#1452): every value as {@code {"t": tag, "v": canonical text}}, over a CLOSED list of tags —
 * the classes the table writer knows ({@code SchemaInferrer} infers a column by Java type, {@code RowHasher} hashes the
 * value as Jackson writes it), so a resumed run lands the same types and the same hashes as one that never stopped. A
 * scalar is matched by its EXACT class, so it decodes to that class; a list or a map decodes to an ArrayList or a
 * LinkedHashMap, a map as ordered pairs (jsonb drops object key order, and a nested map is hashed in its order).
 * Anything else is refused, never stored as text.
 */
public final class CarryCodec {

  public static final int MAX_BYTES = 65_536;
  static final int VERSION = 1;
  private static final ObjectMapper M = new ObjectMapper();

  /** One scalar type: its exact class, its tag, and the canonical text both ways. */
  private record Tag(Class<?> type, String name, Function<Object, String> text, Function<String, Object> value) {}

  /** The closed list, written once: a type added here is encoded and decoded, or not at all. */
  private static final List<Tag> TAGS = List.of(
      new Tag(String.class, "text", Object::toString, v -> v),
      new Tag(Boolean.class, "bool", Object::toString, Boolean::valueOf),
      new Tag(Integer.class, "int", Object::toString, Integer::valueOf),
      new Tag(Long.class, "long", Object::toString, Long::valueOf),
      new Tag(Short.class, "short", Object::toString, Short::valueOf),
      new Tag(Byte.class, "byte", Object::toString, Byte::valueOf),
      new Tag(BigInteger.class, "bigint", Object::toString, BigInteger::new),
      new Tag(BigDecimal.class, "decimal", Object::toString, BigDecimal::new),   // toString keeps the scale
      new Tag(Double.class, "double", Object::toString, Double::valueOf),
      new Tag(Float.class, "float", Object::toString, Float::valueOf),
      new Tag(LocalDate.class, "date", Object::toString, LocalDate::parse),
      // Its instant, not its local date: the JVM's zone and a time of day would not survive toLocalDate().
      new Tag(java.sql.Date.class, "sqldate", d -> String.valueOf(((java.sql.Date) d).getTime()),
          v -> new java.sql.Date(Long.parseLong(v))),
      new Tag(Date.class, "utildate", d -> String.valueOf(((Date) d).getTime()), v -> new Date(Long.parseLong(v))),
      new Tag(Instant.class, "instant", Object::toString, Instant::parse),
      new Tag(LocalDateTime.class, "ldt", Object::toString, LocalDateTime::parse),
      new Tag(OffsetDateTime.class, "odt", Object::toString, OffsetDateTime::parse),
      new Tag(ZonedDateTime.class, "zdt", Object::toString, ZonedDateTime::parse),
      // ISO keeps the nanoseconds.
      new Tag(Timestamp.class, "timestamp", t -> ((Timestamp) t).toInstant().toString(),
          v -> Timestamp.from(Instant.parse(v))));
  private static final Map<Class<?>, Tag> BY_TYPE =
      TAGS.stream().collect(Collectors.toUnmodifiableMap(Tag::type, t -> t));
  private static final Map<String, Tag> BY_NAME =
      TAGS.stream().collect(Collectors.toUnmodifiableMap(Tag::name, t -> t));

  private CarryCodec() {}

  public static JsonNode encode(MappingCarry carry) {
    ObjectNode root = M.createObjectNode();
    root.put("version", VERSION);
    root.put("fingerprint", carry.fingerprint());
    ObjectNode declarations = root.putObject("declarations");
    carry.declarations().forEach((declaration, columns) -> {
      ObjectNode node = declarations.putObject(declaration);
      columns.forEach((column, value) -> node.set(column, envelope(value, declaration + " '" + column + "'")));
    });
    int bytes;
    try {
      bytes = M.writeValueAsBytes(root).length;
    } catch (JsonProcessingException e) {
      throw new CarryRefusedException("the carried values could not be written: " + e.getOriginalMessage());
    }
    if (bytes > MAX_BYTES) {
      throw new CarryRefusedException("the carried values take " + bytes + " bytes, above 64 KiB — a carried value "
          + "is a header, not a document");
    }
    return root;
  }

  /** Null for nothing stored (a SQL NULL, a JSON null). */
  public static MappingCarry decode(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) return null;
    if (node.path("version").asInt(-1) != VERSION || !node.path("fingerprint").isTextual()
        || !node.path("declarations").isObject()) {
      throw new StaleCarryException("the stored carried values are of another format (version "
          + node.path("version") + ") — they cannot be reused; " + StaleCarryException.RESET);
    }
    Map<String, Map<String, Object>> declarations = new LinkedHashMap<>();
    try {
      node.path("declarations").fields().forEachRemaining(d -> {
        Map<String, Object> columns = new LinkedHashMap<>();
        d.getValue().fields().forEachRemaining(c -> columns.put(c.getKey(), value(c.getValue())));
        declarations.put(d.getKey(), columns);
      });
    } catch (StaleCarryException stale) {
      throw stale;
    } catch (RuntimeException malformed) {
      // A pair that is not one, a number or a date that does not parse: never reused, never a raw exception.
      throw new StaleCarryException("the stored carried values are malformed — they cannot be reused; "
          + StaleCarryException.RESET);
    }
    return new MappingCarry(node.path("fingerprint").asText(), declarations);
  }

  private static JsonNode envelope(Object v, String where) {
    ObjectNode env = M.createObjectNode();
    if (v == null) return env.put("t", "null");
    if (v instanceof List<?> list) {
      ArrayNode items = env.put("t", "list").putArray("v");
      for (int i = 0; i < list.size(); i++) items.add(envelope(list.get(i), where + "[" + i + "]"));
      return env;
    }
    if (v instanceof Map<?, ?> map) {
      ArrayNode pairs = env.put("t", "map").putArray("v");
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!(e.getKey() instanceof String key)) {
          String what = e.getKey() == null ? "null" : e.getKey().getClass().getName();
          throw new CarryRefusedException(where + ": a map key that is not text (" + what + ") cannot be carried");
        }
        pairs.addArray().add(key).add(envelope(e.getValue(), where + "." + key));
      }
      return env;
    }
    Tag tag = BY_TYPE.get(v.getClass());
    if (tag == null) {
      throw new CarryRefusedException(where + ": a value of class " + v.getClass().getName() + " cannot be carried");
    }
    return env.put("t", tag.name()).put("v", tag.text().apply(v));
  }

  /** Reads one envelope exactly as {@link #envelope} writes it; any other shape is malformed, never read as empty. */
  private static Object value(JsonNode env) {
    String t = env.path("t").asText();
    JsonNode v = env.path("v");
    return switch (t) {
      case "null" -> null;
      case "list" -> {
        shape(v.isArray());
        List<Object> out = new ArrayList<>();
        v.forEach(item -> out.add(value(item)));
        yield out;
      }
      case "map" -> {
        shape(v.isArray());
        Map<String, Object> out = new LinkedHashMap<>();
        v.forEach(pair -> {
          shape(pair.isArray() && pair.size() == 2 && pair.get(0).isTextual());
          out.put(pair.get(0).asText(), value(pair.get(1)));
        });
        yield out;
      }
      default -> {
        Tag tag = BY_NAME.get(t);
        if (tag == null) {
          throw new StaleCarryException("a stored carried value has an unknown type tag: " + t + "; "
              + StaleCarryException.RESET);
        }
        shape(v.isTextual());
        yield tag.value().apply(v.asText());
      }
    };
  }

  /** A shape {@link #envelope} never writes: caught by {@link #decode} and refused as malformed. */
  private static void shape(boolean asWritten) {
    if (!asWritten) throw new IllegalArgumentException("not the shape the codec writes");
  }

}

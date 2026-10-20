package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
public class RowHasher {

  // parse-date is the one hook that puts a java.time value in the row — LocalDate or LocalDateTime
  // depending on the declared format (ParseDateHook#apply); its siblings produce types Jackson
  // already handles (parse-number → BigDecimal, the rest → String). Every row is hashed here on
  // write (MultiTargetWriter.java:104), and without JSR-310 registered Jackson refuses the type and
  // the chunk fails — so a date that parses SUCCESSFULLY used to kill the run. registerModule is
  // explicit rather than findAndRegisterModules() so the behaviour is pinned, not classpath-order
  // dependent.
  //
  // A jdbc time with no zone hashes as its WallClockTimestamp says (#1328) — here only, so the
  // dead-letter payload and every other writer still get a plain Timestamp.
  private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule())
      .registerModule(new SimpleModule().addSerializer(WallClockTimestamp.class, new StdSerializer<>(WallClockTimestamp.class) {
        @Override
        public void serialize(WallClockTimestamp t, JsonGenerator gen, SerializerProvider provider) throws IOException {
          provider.defaultSerializeValue(t.hashed(), gen);
        }
      }));

  /**
   * Compute a stable SHA-256 hex hash of the row.
   * Keys are sorted to ensure order-independence.
   */
  public String hash(Map<String, Object> row) {
    try {
      TreeMap<String, Object> sorted = new TreeMap<>(row);
      String canonical = M.writeValueAsString(sorted);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      // Lower-case, no delimiter: the exact text the per-byte String.format("%02x") loop built,
      // without its 32 format-string parses per row — most of the hash's cost (#910).
      return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException("RowHasher: " + e.getMessage(), e);
    }
  }
}

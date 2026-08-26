package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
public class RowHasher {

  // parse-date is the one hook that puts a java.time value in the row — LocalDate or LocalDateTime
  // depending on the declared format (ParseDateHook.java:40-42); its siblings produce types Jackson
  // already handles (parse-number → BigDecimal, the rest → String). Every row is hashed here on
  // write (MultiTargetWriter.java:104), and without JSR-310 registered Jackson refuses the type and
  // the chunk fails — so a date that parses SUCCESSFULLY used to kill the run. registerModule is
  // explicit rather than findAndRegisterModules() so the behaviour is pinned, not classpath-order
  // dependent.
  private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());

  /**
   * Compute a stable SHA-256 hex hash of the row.
   * Keys are sorted to ensure order-independence.
   */
  public String hash(Map<String, Object> row) {
    try {
      TreeMap<String, Object> sorted = new TreeMap<>(row);
      String canonical = M.writeValueAsString(sorted);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException("RowHasher: " + e.getMessage(), e);
    }
  }
}

package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class RowHasherTest {

  @Inject RowHasher hasher;

  @Test
  void same_row_produces_same_hash() {
    var r1 = Map.<String,Object>of("a", "1", "b", "2");
    var r2 = Map.<String,Object>of("b", "2", "a", "1"); // different order
    assertThat(hasher.hash(r1)).isEqualTo(hasher.hash(r2));
  }

  @Test
  void different_row_produces_different_hash() {
    assertThat(hasher.hash(Map.of("a","1"))).isNotEqualTo(hasher.hash(Map.of("a","2")));
  }

  @Test
  void hash_format_is_hex_sha256() {
    assertThat(hasher.hash(Map.of("a","1"))).matches("[0-9a-f]{64}");
  }

  /**
   * A successful {@code parse-date} leaves a {@link LocalDate}/{@link LocalDateTime} in the row
   * (ParseDateHook.java:40-42), and {@code MultiTargetWriter.java:104} hashes that row on every
   * write. Without JSR-310 registered, Jackson refuses the type and the chunk fails — reproduced
   * here directly against the row shape a successful parse actually produces.
   */
  @Test
  void a_row_carrying_a_local_date_hashes_without_throwing() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("dob", LocalDate.of(2024, 1, 15));
    assertThat(hasher.hash(row)).matches("[0-9a-f]{64}");
  }

  @Test
  void a_row_carrying_a_local_date_time_hashes_without_throwing() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("created", LocalDateTime.of(2024, 1, 15, 10, 30, 0));
    assertThat(hasher.hash(row)).matches("[0-9a-f]{64}");
  }

  /**
   * Pin against a stored hash silently moving under existing rows. Registering JavaTimeModule
   * only ADDS ser/deser support for java.time types — it must not change how a type Jackson
   * already handled (String/Number/Boolean/null) serializes. This recomputes the row's hash with
   * a bare, module-less {@code ObjectMapper} — exactly {@link RowHasher}'s algorithm before this
   * fix, since a plain-scalar row never touched the missing module either way — and compares it
   * to the (now JSR-310-registered) production {@link RowHasher#hash}. No existing stored hash
   * moves: no row ever reached the database carrying a temporal value in the first place, because
   * that path always threw before this fix (see the two tests above, red without it).
   */
  @Test
  void plain_scalar_row_hash_is_unchanged_by_the_jsr310_fix() throws Exception {
    Map<String, Object> row = Map.of("a", "1", "b", 2, "c", true);

    ObjectMapper bare = new ObjectMapper();
    TreeMap<String, Object> sorted = new TreeMap<>(row);
    String canonical = bare.writeValueAsString(sorted);
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(64);
    for (byte b : digest) sb.append(String.format("%02x", b));
    String beforeFix = sb.toString();

    assertThat(hasher.hash(row)).isEqualTo(beforeFix);
  }
}

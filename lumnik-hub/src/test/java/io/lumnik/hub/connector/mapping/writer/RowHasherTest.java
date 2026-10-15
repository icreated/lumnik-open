package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

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
   * (ParseDateHook#apply), and {@code MultiTargetWriter.java:104} hashes that row on every
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

  /**
   * {@code _row_hash} is a persisted dedup key: a hash that moves under an unchanged row re-inserts
   * it as new. These literals were produced by the implementation as it stood before #910 changed
   * how the digest is computed and hex-encoded — every value shape a row reaches the hasher with:
   * plain scalars, nulls, non-ASCII text (accents, emoji, CJK), the java.time / BigDecimal values
   * parse-date and parse-number leave behind, a nested list/map, and the empty row.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("persistedHashes")
  void hash_is_byte_identical_to_the_persisted_form(String shape, Map<String, Object> row, String expected) {
    assertThat(hasher.hash(row)).isEqualTo(expected);
  }

  static Stream<Arguments> persistedHashes() {
    Map<String, Object> nulls = new HashMap<>();
    nulls.put("name", null);
    nulls.put("x", "y");
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("tags", List.of("x", "y"));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("k", "v");
    meta.put("a", 1);
    nested.put("meta", meta);
    return Stream.of(
        Arguments.of("scalars", Map.of("a", "1", "b", 2, "c", true),
            "b4fcd94826c3c3f098c0aff42ad8c2a1b5e4d951e56208aa43c6161ac90109d6"),
        Arguments.of("nulls", nulls,
            "cbc9b0571a1031834d5baa7da0795d4f4bd80e795cf3478c67146aa3fd2192f6"),
        Arguments.of("unicode", Map.of("nom", "Élodie Gaëlle", "ville", "Zürich", "emoji", "🧊→💧", "cjk", "東京"),
            "f1597cd1bd1236cfba0c8eb2e484c1585103720b39a171d3c90d9311a5f01434"),
        Arguments.of("typed", Map.of("dob", LocalDate.of(2024, 1, 15),
                "created", LocalDateTime.of(2024, 1, 15, 10, 30),
                "amount", new BigDecimal("1234.50"), "n", 42L, "f", 1.5d),
            "79b1eaa2d7da238a66f4d23ac97a7768af42c332bf13f71d7869925366a9709a"),
        Arguments.of("nested", nested,
            "19e52e6dc3f773fd9b5faae7459436a5a92280639cd0010c9e5a18603e159de7"),
        // sha256("{}") — the one value checkable by hand: `printf '{}' | shasum -a 256`
        Arguments.of("empty", Map.of(),
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
  }
}

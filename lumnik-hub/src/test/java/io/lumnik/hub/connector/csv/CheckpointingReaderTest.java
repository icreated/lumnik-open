package io.lumnik.hub.connector.csv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The equivalence property IS the contract (lot-5b design (A)): for every char position P the
 * reader has delivered, {@code checkpoint(P)} names a byte anchor + char delta such that a FRESH
 * decoder started at the anchor, skipping delta chars, reads exactly the chars a straight
 * read-through would have delivered from P on. The matrix forces multi-byte sequences to straddle
 * decode-batch boundaries (small batch sizes make it deterministic, not probabilistic) and tests
 * EVERY checkpoint position — start, mid-batch, batch-boundary, end — because the boundary cases
 * are the whole point.
 */
class CheckpointingReaderTest {

  /** Batch sizes chosen so multi-byte sequences straddle boundaries at many different phases. */
  private static final int[] BATCH_SIZES = {16, 17, 64};

  private record Scenario(String name, Charset cs, String content) {}

  /**
   * Charsets × content shapes. Every content string round-trips through its charset (verified in
   * the test itself), so {@code content.substring(P)} is the oracle for a resume at P.
   */
  private static List<Scenario> scenarios() {
    // 2-byte (é), 3-byte (€), 4-byte (😀 = surrogate pair) UTF-8 sequences, densely repeated so
    // some sequence straddles every batch boundary at every tested batch size.
    String utf8Dense = "é€😀àß✓".repeat(40);
    // A 2-byte char whose bytes are exactly 63–64: straddles the boundary at batchSize 64 head-on.
    String utf8AtBoundary = "a".repeat(63) + "é" + "b".repeat(70) + "€" + "c".repeat(40);
    // CSV-shaped content: long lines and quoted fields containing newlines — the record-is-not-a-
    // line reality the design was built for.
    String quotedNewlines = "id,note\n1,\"première ligne\nseconde ligne\"\n2,\"état: préparé\"\n"
        .repeat(12);
    String longLines = ("x".repeat(120) + ",état," + "y".repeat(90) + "\n").repeat(4);
    // UTF-16 content leads with an explicit BOM char: under UTF-16LE/BE the decoder is
    // BOM-agnostic, so U+FEFF must count and resume like any other char.
    String utf16Content = "\uFEFF" + "ab,😀 délai\nc,\"fin\nde ligne\"\n".repeat(15);
    String latin1 = "café, naïve; règle ±°µ½Ã\n".repeat(20);
    String cp1252 = "€uro “quoted” –dash– œuvre™\n".repeat(20);
    return List.of(
        new Scenario("utf8-dense-multibyte", StandardCharsets.UTF_8, utf8Dense),
        new Scenario("utf8-char-at-boundary", StandardCharsets.UTF_8, utf8AtBoundary),
        new Scenario("utf8-quoted-newlines", StandardCharsets.UTF_8, quotedNewlines),
        new Scenario("utf8-long-lines", StandardCharsets.UTF_8, longLines),
        new Scenario("utf16le-bom-surrogates", StandardCharsets.UTF_16LE, utf16Content),
        new Scenario("utf16be-bom-surrogates", StandardCharsets.UTF_16BE, utf16Content),
        new Scenario("iso-8859-1", StandardCharsets.ISO_8859_1, latin1),
        new Scenario("windows-1252", Charset.forName("windows-1252"), cp1252));
  }

  // ---------------------------------------------------------------------------------------------
  // The equivalence property
  // ---------------------------------------------------------------------------------------------

  @Test
  void straight_read_equals_checkpoint_plus_resume_for_every_position() throws Exception {
    for (Scenario sc : scenarios()) {
      byte[] bytes = sc.content().getBytes(sc.cs());
      // Round-trip sanity: the oracle (substring) is only valid if the content survives encoding.
      assertThat(new String(bytes, sc.cs())).as("%s round-trips", sc.name()).isEqualTo(sc.content());
      for (int batchSize : BATCH_SIZES) {
        var reader = new CheckpointingReader(new ByteArrayInputStream(bytes), sc.cs(), batchSize);
        String all = readFully(reader);
        assertThat(all).as("%s b=%d straight read", sc.name(), batchSize).isEqualTo(sc.content());
        // EVERY position: start (0), every mid-batch and batch-boundary position, end (length).
        for (int p = 0; p <= sc.content().length(); p++) {
          CheckpointingReader.Checkpoint cp = reader.checkpoint(p);
          String tail = readFully(resumeAt(bytes, sc.cs(), cp, batchSize));
          if (!tail.equals(sc.content().substring(p))) {
            // assertThat on every iteration is slow; assert only on divergence, with coordinates.
            assertThat(tail)
                .as("%s b=%d P=%d anchor=%d delta=%d", sc.name(), batchSize, p,
                    cp.anchorByte(), cp.charDelta())
                .isEqualTo(sc.content().substring(p));
          }
        }
        reader.close();
      }
    }
  }

  @Test
  void checkpoint_mid_read_names_only_delivered_positions() throws Exception {
    String content = "é€😀".repeat(30);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try (var reader = new CheckpointingReader(
        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, 16)) {
      char[] buf = new char[25];
      int delivered = 0;
      while (delivered < 25) {
        delivered += reader.read(buf, delivered, 25 - delivered);
      }
      // A position already delivered checkpoints fine, mid-read, ledger still growing.
      CheckpointingReader.Checkpoint cp = reader.checkpoint(20);
      String tail = readFully(resumeAt(bytes, StandardCharsets.UTF_8, cp, 16));
      assertThat(tail).isEqualTo(content.substring(20));
      // A position beyond what was delivered is a caller bug: fail loud, not garbage later.
      assertThatThrownBy(() -> reader.checkpoint(26))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not been delivered");
      assertThatThrownBy(() -> reader.checkpoint(-1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void resumed_reader_is_itself_checkpointable_in_anchor_relative_coordinates() throws Exception {
    String content = "état: préparé — 😀 fin\n".repeat(20);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try (var first = new CheckpointingReader(
        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, 17)) {
      readFully(first);
      int p1 = 100;
      CheckpointingReader.Checkpoint cp1 = first.checkpoint(p1);
      // Resume, read some more, checkpoint AGAIN from the resumed reader: its coordinates are
      // relative to the anchor (bytes) / to the anchor's first char (chars, delta included).
      CheckpointingReader second = resumeAt(bytes, StandardCharsets.UTF_8, cp1, 17);
      char[] buf = new char[40];
      int got = 0;
      while (got < 40) {
        got += second.read(buf, got, 40 - got);
      }
      long p2Relative = cp1.charDelta() + 40;  // chars since cp1's anchor
      CheckpointingReader.Checkpoint cp2 = second.checkpoint(p2Relative);
      long absoluteAnchor = cp1.anchorByte() + cp2.anchorByte();
      String tail = readFully(resumeAt(bytes, StandardCharsets.UTF_8,
          new CheckpointingReader.Checkpoint(absoluteAnchor, cp2.charDelta()), 17));
      assertThat(tail).isEqualTo(content.substring(p1 + 40));
      second.close();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The allowlist
  // ---------------------------------------------------------------------------------------------

  @Test
  void off_allowlist_charset_reads_fine_but_checkpoint_throws_the_documented_exception()
      throws Exception {
    // ISO-2022-JP is stateful (escape sequences select the active sub-charset): no mid-stream
    // byte position is a clean fresh-decoder start, so checkpointing it would be silently wrong.
    Charset iso2022 = Charset.forName("ISO-2022-JP");
    String content = "日本語のテキスト abc 123\n".repeat(5);
    byte[] bytes = content.getBytes(iso2022);
    assertThat(new String(bytes, iso2022)).isEqualTo(content);  // round-trip sanity
    try (var reader = new CheckpointingReader(new ByteArrayInputStream(bytes), iso2022, 32)) {
      assertThat(readFully(reader)).isEqualTo(content);          // plain reading still works
      assertThatThrownBy(() -> reader.checkpoint(3))
          .isInstanceOf(CheckpointingReader.UncheckpointableCharsetException.class)
          .hasMessageContaining("ISO-2022-JP");
    }
    assertThat(CheckpointingReader.canCheckpoint(iso2022)).isFalse();
  }

  @Test
  void plain_utf16_is_off_the_allowlist_because_its_decoder_is_bom_stateful() throws Exception {
    // Charset "UTF-16" resolves endianness from an initial BOM and strips it — state a fresh
    // decoder at a mid-stream anchor cannot reproduce (it would default to big-endian and would
    // not know a BOM was already consumed). UTF-16BE / UTF-16LE are the checkpointable spellings.
    Charset utf16 = StandardCharsets.UTF_16;
    String content = "checkpoint me\n".repeat(10);
    byte[] bytes = content.getBytes(utf16);   // getBytes(UTF-16) writes a BOM
    try (var reader = new CheckpointingReader(new ByteArrayInputStream(bytes), utf16, 32)) {
      assertThat(readFully(reader)).isEqualTo(content);          // plain reading still works
      assertThatThrownBy(() -> reader.checkpoint(0))
          .isInstanceOf(CheckpointingReader.UncheckpointableCharsetException.class);
    }
    assertThat(CheckpointingReader.canCheckpoint(utf16)).isFalse();
    assertThat(CheckpointingReader.canCheckpoint(StandardCharsets.UTF_16LE)).isTrue();
    assertThat(CheckpointingReader.canCheckpoint(StandardCharsets.UTF_16BE)).isTrue();
  }

  @Test
  void resume_refuses_off_allowlist_charsets_symmetrically() {
    assertThatThrownBy(() -> CheckpointingReader.resume(
        new ByteArrayInputStream(new byte[0]), Charset.forName("ISO-2022-JP"), 0))
        .isInstanceOf(CheckpointingReader.UncheckpointableCharsetException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Proof that the property CAN fail — a test that cannot fail is worthless
  // ---------------------------------------------------------------------------------------------

  @Test
  void resume_from_a_wrong_anchor_is_detected_not_silently_equal() throws Exception {
    // Dense 2-byte content: an anchor shifted by one byte lands mid-sequence, so the fresh
    // decoder either throws MalformedInputException (REPORT semantics) or produces a char
    // stream that differs from the oracle. Either outcome proves the equivalence assertion has
    // teeth — a clean pass here would mean the property test could never catch a broken ledger.
    String content = "éèêëàâäîïôöùûüç".repeat(20);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try (var reader = new CheckpointingReader(
        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, 16)) {
      readFully(reader);
      CheckpointingReader.Checkpoint cp = reader.checkpoint(40);
      assertThat(cp.anchorByte()).isPositive();  // the corruption below must actually shift it
      var corrupted = new CheckpointingReader.Checkpoint(cp.anchorByte() + 1, cp.charDelta());
      Throwable thrown = catchThrowable(() -> {
        String tail = readFully(resumeAt(bytes, StandardCharsets.UTF_8, corrupted, 16));
        assertThat(tail).isNotEqualTo(content.substring(40));
      });
      if (thrown != null) {
        // Malformed mid-sequence bytes surface as a coding exception — detection, not silence.
        assertThat(thrown).isInstanceOfAny(CharacterCodingException.class, IOException.class);
      }
    }
  }

  @Test
  void resume_on_a_stream_shorter_than_the_delta_fails_loud() {
    byte[] bytes = "short".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> CheckpointingReader.resume(
        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, 99))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ended");
  }

  // ---------------------------------------------------------------------------------------------
  // Construction guards
  // ---------------------------------------------------------------------------------------------

  @Test
  void batch_size_must_leave_room_for_carried_leftover_bytes() {
    assertThatThrownBy(() -> new CheckpointingReader(
        new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8, 4))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Simulates the transport's byte-seek: an InputStream already positioned at the anchor. */
  private static CheckpointingReader resumeAt(byte[] bytes, Charset cs,
                                              CheckpointingReader.Checkpoint cp,
                                              int batchSize) throws IOException {
    InputStream at = new ByteArrayInputStream(
        bytes, (int) cp.anchorByte(), bytes.length - (int) cp.anchorByte());
    return CheckpointingReader.resume(at, cs, cp.charDelta(), batchSize);
  }

  private static String readFully(Reader r) throws IOException {
    StringWriter out = new StringWriter();
    r.transferTo(out);
    return out.toString();
  }
}

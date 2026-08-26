package io.lumnik.hub.connector.csv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extended cursor shape (lot-5b): optional checkpoint fields riding on the legacy
 * two-field cursor. Compatibility is the contract — both directions, no migration:
 * a legacy cursor deserializes unchanged and lands on the fallback path; an extended
 * cursor still carries valid legacy fields for an old build (or a fallback window) to use.
 */
class CsvCursorCheckpointTest {

  @Test
  void legacy_two_field_json_deserializes_unchanged_and_has_no_checkpoint() {
    CsvCursor c = CsvCursor.fromJson("{\"file\":\"/imports/x.csv\",\"rowsProcessed\":42}");
    assertThat(c.file()).isEqualTo("/imports/x.csv");
    assertThat(c.rowsProcessed()).isEqualTo(42L);
    assertThat(c.hasCheckpoint()).as("legacy cursor must land on the fallback path").isFalse();
  }

  @Test
  void unknown_fields_are_ignored_so_newer_cursors_do_not_break_a_parse() {
    // The persisted cursor node also carries "type":"CsvFile"; and a build that predates a
    // future field must not explode on it. Tolerant reads are the compatibility contract.
    CsvCursor c = CsvCursor.fromJson(
        "{\"type\":\"CsvFile\",\"file\":\"a.csv\",\"rowsProcessed\":7,\"someFutureField\":true}");
    assertThat(c.file()).isEqualTo("a.csv");
    assertThat(c.rowsProcessed()).isEqualTo(7L);
  }

  @Test
  void legacy_cursor_serializes_exactly_as_before_no_new_fields() {
    // The two-field shape is what non-checkpointable windows keep writing; it must not grow
    // null-valued noise (an old build's manual field reads would survive it, but the shape
    // IS the compatibility statement).
    assertThat(new CsvCursor("x.csv", 3).toJson())
        .isEqualTo("{\"file\":\"x.csv\",\"rowsProcessed\":3}");
  }

  @Test
  void extended_cursor_round_trips_with_all_checkpoint_fields() {
    CsvCursor c = new CsvCursor("/imports/x.csv", 500, 8192L, 117L, 12L,
        java.util.List.of("id", "name"),
        new CsvCursor.Version(102400L, "2026-08-12T10:15:30.123456Z", null));
    CsvCursor back = CsvCursor.fromJson(c.toJson());
    assertThat(back).isEqualTo(c);
    assertThat(back.hasCheckpoint()).isTrue();
    // The legacy fields stay first-class: they ARE the fallback position.
    assertThat(back.file()).isEqualTo("/imports/x.csv");
    assertThat(back.rowsProcessed()).isEqualTo(500L);
  }

  @Test
  void checkpoint_requires_every_field_and_a_sane_anchor() {
    var headers = java.util.List.of("id");
    var v = new CsvCursor.Version(100L, "2026-08-12T10:15:30Z", null);
    assertThat(new CsvCursor("f", 1, 10L, 0L, 3L, headers, v).hasCheckpoint()).isTrue();
    // missing pieces → fallback
    assertThat(new CsvCursor("f", 1, null, 0L, 3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, null, 3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, 0L, null, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, 0L, 3L, null, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, 0L, 3L, java.util.List.of(), v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, 0L, 3L, headers, null).hasCheckpoint()).isFalse();
    // corrupt coordinates → fallback: a negative anchor/delta, or an anchor at/past EOF,
    // cannot name a record start in a file of version.size bytes
    assertThat(new CsvCursor("f", 1, -1L, 0L, 3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, -5L, 3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 10L, 0L, -3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 100L, 0L, 3L, headers, v).hasCheckpoint()).isFalse();
    assertThat(new CsvCursor("f", 1, 250L, 0L, 3L, headers, v).hasCheckpoint()).isFalse();
  }

  @Test
  void version_token_matches_only_the_same_bytes() {
    var lm = java.time.Instant.parse("2026-08-12T10:15:30.000000042Z");
    var v = new CsvCursor.Version(100L, lm.toString(), null);
    var same = new io.lumnik.hub.connector.transport.FileDescriptor("/f", 100L, lm, null);
    assertThat(v.matches(same)).isTrue();
    // nanosecond-precision mtimes must round-trip exactly — no epoch-millis truncation
    assertThat(v.matches(new io.lumnik.hub.connector.transport.FileDescriptor(
        "/f", 100L, java.time.Instant.parse("2026-08-12T10:15:30Z"), null))).isFalse();
    assertThat(v.matches(new io.lumnik.hub.connector.transport.FileDescriptor("/f", 99L, lm, null)))
        .isFalse();
    assertThat(v.matches(null)).isFalse();
    // etag participates only when both sides carry one (FileDescriptor.sameVersion semantics)
    var withEtag = new CsvCursor.Version(100L, lm.toString(), "abc");
    assertThat(withEtag.matches(new io.lumnik.hub.connector.transport.FileDescriptor("/f", 100L, lm, "abc"))).isTrue();
    assertThat(withEtag.matches(new io.lumnik.hub.connector.transport.FileDescriptor("/f", 100L, lm, "zzz"))).isFalse();
    assertThat(withEtag.matches(new io.lumnik.hub.connector.transport.FileDescriptor("/f", 100L, lm, null))).isTrue();
    // an unparseable stored timestamp is a corrupt token, never a match
    assertThat(new CsvCursor.Version(100L, "not-a-time", null).matches(same)).isFalse();
    assertThat(new CsvCursor.Version(100L, null, null).matches(same)).isFalse();
  }
}

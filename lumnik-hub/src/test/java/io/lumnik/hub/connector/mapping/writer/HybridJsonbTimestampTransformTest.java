package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import io.lumnik.hub.view.ViewFilter;
import io.lumnik.hub.sql.TemporalText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code parse_iso8601} against the shapes an ERP actually exports.
 *
 * <p>The finding this pins: {@code OffsetDateTime.parse} REQUIRES an offset, so the single most
 * common ERP timestamp — {@code 2008-04-18T00:00:00}, a valid ISO-8601 local date-time — failed
 * the whole run at index 19, the position of the offset that was never there.
 *
 * <p>The forms below are not a wish list. They are what a {@link ViewFilter} has always accepted
 * on the READ side of the same column, and the last test here is the reason that matters: a value
 * written by ingestion and a filter typed against it must mean the same instant, or the operator
 * filters on the value shown on screen and gets nothing back.
 */
class HybridJsonbTimestampTransformTest {

  private static Object parse(Object raw) {
    return HybridJsonbWriter.applyTransform(raw, Optional.of(Transform.PARSE_ISO8601), "ordered_at");
  }

  @ParameterizedTest(name = "[{index}] {0} → {1}")
  @CsvSource({
      // the finding itself: the ERP's naive local timestamp, read as UTC
      "2008-04-18T00:00:00,        2008-04-18T00:00:00Z",
      // PostgreSQL's own text rendering — a space where ISO wants a T
      "2008-04-18 00:00:00,        2008-04-18T00:00:00Z",
      "2026-08-01 10:04:56.019386, 2026-08-01T10:04:56.019386Z",
      // date only: midnight UTC, the same convention the view filter states
      "2008-04-18,                 2008-04-18T00:00:00Z",
      // what already worked must keep working
      "2026-08-01T12:30:00Z,       2026-08-01T12:30:00Z",
      "2026-08-01T14:30:00+02:00,  2026-08-01T12:30:00Z",
      // a whole-hour offset written PostgreSQL-style, without the minutes ISO demands
      "2026-08-01T12:30:00+00,     2026-08-01T12:30:00Z",
  })
  void the_shapes_an_erp_exports_are_read_as_instants(String raw, String expected) {
    // Compared as instants, never as OffsetDateTime: equals() there is offset-sensitive, so
    // 14:30+02:00 and 12:30Z — the same moment, and the same row in the column — differ.
    assertThat(((OffsetDateTime) parse(raw)).toInstant()).isEqualTo(Instant.parse(expected));
  }

  /**
   * A value that is genuinely not a timestamp must still fail the run — the point of the fix is to
   * stop refusing valid ISO-8601, not to start inventing dates. What changes is only the wording.
   */
  @ParameterizedTest
  @ValueSource(strings = {"18/04/2008", "not-a-date", "2026-02-30", ""})
  void a_value_that_is_not_a_timestamp_still_refuses(String raw) {
    assertThatThrownBy(() -> parse(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ordered_at")        // WHICH column, on a wide promote block
        .hasMessageContaining("parse_iso8601")     // and which transform is doing the refusing
        // Quoted, not merely present: every string contains "", so an unquoted check would be
        // vacuously true for the empty value — the one case where seeing the delimiters is the
        // whole point, since the message would otherwise read "cannot read  as a timestamp".
        .hasMessageContaining("'" + raw + "'");
  }

  /**
   * The guard against the two doors drifting apart, driven through the view's real gate rather
   * than through the shared parser — which both sides call, and which therefore cannot disagree
   * with itself.
   *
   * <p>What can disagree is {@link ViewFilter#TEMPORAL_SHAPE}: a separate regex that decides
   * whether a filter's operand is temporal at all, before the parse is ever attempted. A shape
   * this writer stores but that regex rejects gives the operator a refused filter on a column
   * full of exactly those values.
   *
   * <p>{@code ViewFilterTest.everyTemporalShapeMatchIsAlsoParseable_noRegexParserDrift} guards the
   * opposite direction — that everything the regex admits can be parsed. Neither subsumes the
   * other: that one starts from a hand-written list of shapes, this one from what the writer
   * actually stores.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "2008-04-18T00:00:00", "2008-04-18 00:00:00", "2008-04-18",
      "2026-08-01T12:30:00Z", "2026-08-01T14:30:00+02:00", "2026-08-01T12:30:00+00",
  })
  void every_shape_ingestion_stores_is_a_shape_a_view_filter_accepts(String raw) {
    Instant written = ((OffsetDateTime) parse(raw)).toInstant();

    ViewFilter typed = ViewFilter.parse("ordered_at>" + raw);
    assertThat(typed.operandType())
        .as("the view's own gate must recognise a value this writer stores")
        .isEqualTo(ViewFilter.OperandType.TEMPORAL);
    assertThat(TemporalText.parse(typed.value()))
        .as("and compare it against the instant that was actually written")
        .isEqualTo(written);
  }
}

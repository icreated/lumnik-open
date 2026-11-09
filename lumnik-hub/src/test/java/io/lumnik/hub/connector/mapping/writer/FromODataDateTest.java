package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1826: SAP Gateway writes a date as {@code /Date(<ms>)/}, a DateTimeOffset as {@code /Date(<ms>±<minutes>)/}.
 * The milliseconds are the UTC instant; the offset only says which zone the source showed it in.
 */
class FromODataDateTest {

  private static Object read(Object raw) {
    return HybridJsonbWriter.applyTransform(raw, Optional.of(Transform.FROM_ODATA_DATE), "requested_delivery");
  }

  @ParameterizedTest(name = "[{index}] {0} → {1}")
  @CsvSource({
      "/Date(1793577600000)/,        2026-11-02T00:00:00Z",
      "/Date(1793577600000+0000)/,   2026-11-02T00:00:00Z",
      // the offset is the source's zone, not a shift of the instant
      "/Date(1793577600000+0060)/,   2026-11-02T00:00:00Z",
      "/Date(1793577600000-0300)/,   2026-11-02T00:00:00Z",
      // before 1970
      "/Date(-86400000)/,            1969-12-31T00:00:00Z",
      "/Date(-86400000-0300)/,       1969-12-31T00:00:00Z",
      "/Date(0)/,                    1970-01-01T00:00:00Z",
      // the two ends SAP uses for "no date" and "no end"
      "/Date(-62135596800000)/,      0001-01-01T00:00:00Z",
      "/Date(253402300799000)/,      9999-12-31T23:59:59Z",
      // the milliseconds are kept
      "/Date(1793577600123)/,        2026-11-02T00:00:00.123Z",
      "'  /Date(1793577600000)/  ',   2026-11-02T00:00:00Z"})
  void reads_the_odata_v2_shapes(String text, String instant) {
    assertThat(read(text)).isEqualTo(OffsetDateTime.parse(instant));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "2026-11-02T00:00:00Z", "1793577600000", "/Date()/", "/Date(abc)/", "/Date(1.5)/",
      "Date(1793577600000)", "/Date(1793577600000)", "/Date(1793577600000+)/", "/Date(1793577600000+00000)/",
      "/Date(1793577600000+1500)/", "/Date(1793577600000)/ x",
      // beyond what a timestamptz holds: one cell, not a run failed by the database
      "/Date(253402300800000)/", "/Date(-62135596800001)/", "/Date(9999999999999999)/"})
  void any_other_shape_reads_no_value_and_counts_a_cell_error(String text) {
    assertThatThrownBy(() -> read(text)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested_delivery").hasMessageContaining("from_odata_date")
        // the offset may be either sign: the message names both, not the plus alone
        .hasMessageContaining("<ms>±<minutes>")
        // and the two limits a well-shaped value can still break
        .hasMessageContaining("1440").hasMessageContaining("0001 to 9999")
        // the cell's value is the source's data: the message speaks about its shape, never quotes it
        .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain(text.strip().isEmpty() ? "\0" : text.strip()));
  }

  @Test
  void a_number_is_not_the_wrapper() {
    assertThatThrownBy(() -> read(1793577600000L)).isInstanceOf(IllegalArgumentException.class);
  }
}

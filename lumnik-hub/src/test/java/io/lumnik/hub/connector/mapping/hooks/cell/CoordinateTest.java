package io.lumnik.hub.connector.mapping.hooks.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.lumnik.hub.connector.mapping.hooks.cell.Coordinate.Axis;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** One cell read as signed degrees, with the axis its hemisphere letter names (#1966). */
class CoordinateTest {

  @ParameterizedTest(name = "{0} reads {1} on {2}")
  @CsvSource(delimiter = '|', nullValues = "-", value = {
      "48.8566        | 48.8566   | -",
      "48,8566        | 48.8566   | -",
      "-1,5536        | -1.5536   | -",
      "+4.8309        | 4.8309    | -",
      "0              | 0         | -",
      "48.8566 N      | 48.8566   | LAT",
      "33.8688 S      | -33.8688  | LAT",
      "2.35E          | 2.35      | LON",
      "1.5536 W       | -1.5536   | LON",
      "W 1.5536       | -1.5536   | LON",
      "O 0.3707       | -0.3707   | LON",
      "n 48.85        | 48.85     | LAT",
      "48°50'12\"N    | 48.836667 | LAT",
      "2°22'58\"E     | 2.382778  | LON",
      "1°40'46\"W     | -1.679444 | LON",
      "43°42'N        | 43.7      | LAT",
      "43 17 42 N     | 43.295    | LAT",
      "0 22 12 W      | -0.37     | LON",
      "48° 50' 12.5\" N | 48.836806 | LAT",
      "48°04′45″N     | 48.079167 | LAT",
      "7°21’30”E      | 7.358333  | LON",
      "47.2184\u00A0N | 47.2184   | LAT",
      "47,2184\u202FN | 47.2184   | LAT",
      "1.5536\u2007W  | -1.5536   | LON"})
  void a_spelling_of_the_closed_list_is_read(String raw, double degrees, Axis axis) {
    assertThat(Coordinate.read(raw)).hasValueSatisfying(reading -> {
      assertThat(reading.degrees()).isCloseTo(degrees, within(0.0000005));
      assertThat(reading.axis()).isEqualTo(axis);
    });
  }

  @ParameterizedTest(name = "\"{0}\" is not a coordinate")
  @ValueSource(strings = {
      "", " ", "n/a", "N", "abc", "1.2.3", "1,2,3", "--5", "+-5", "1e5", "NaN", "Infinity", "0x10",
      "-49.1193 N",          // a sign and a letter: multiplying them would be a guess
      "N 48.85 N",           // a letter on both sides
      "48.85 X",             // a letter of no hemisphere
      "48°50'12\"",          // degrees-minutes-seconds without a hemisphere letter
      "48°60'00\"N",         // sixty minutes
      "48°50'60\"N",         // sixty seconds
      "48.5°30'N",           // decimal degrees with minutes
      "-48°50'12\"N",        // a signed degrees-minutes-seconds
      "48 50 12 30 N",       // four parts
      "30'N",                // minutes alone: thirty minutes are not thirty degrees
      "12\"E",               // seconds alone
      "48'51°12 N",          // the marks in the wrong order
      "48\"51 N",            // a seconds mark after the degrees
      "48°50 12 N",          // marks and spaces mixed
      "48°N"})               // a degree mark and nothing after
  void anything_else_is_not_a_coordinate(String raw) {
    assertThat(Coordinate.read(raw)).isEmpty();
  }
}

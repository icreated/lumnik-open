package io.lumnik.hub.connector.mapping.hooks.cell;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Blank;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Box;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Outcome;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Point;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Reason;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.SetAside;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Two cells read as one GPS point, exchanged when swapped, or set aside with a reason (#1966). */
class CoordinatePairTest {

  private static final Box FRANCE = new Box(41.0, -5.5, 51.5, 10.0);

  private static Outcome point(String lat, String lon) {
    return new Point(new BigDecimal(lat), new BigDecimal(lon), false);
  }

  private static Outcome exchanged(String lat, String lon) {
    return new Point(new BigDecimal(lat), new BigDecimal(lon), true);
  }

  private static Outcome aside(Reason reason) {
    return new SetAside(reason);
  }

  /** The 21 sites of the lab scenario `broken-coordinates`, replayed 2026-11-13: what its runner expects. */
  static Stream<Arguments> theReplayedSites() {
    return Stream.of(
        Arguments.of("S01", "45.7302", "4.8309", point("45.7302", "4.8309")),
        Arguments.of("S02", "45,7719", "4,8902", point("45.7719", "4.8902")),
        Arguments.of("S03", "48°50'12\"N", "2°22'58\"E", point("48.836667", "2.382778")),
        Arguments.of("S04", "47.2184 N", "1.5536 W", point("47.2184", "-1.5536")),
        Arguments.of("S05", "48.3904", "-4.4861", point("48.3904", "-4.4861")),
        Arguments.of("S06", "5.3698", "43.2965", exchanged("43.2965", "5.3698")),
        Arguments.of("S07", "0", "0", aside(Reason.OUTSIDE_THE_AREA)),
        Arguments.of("S08", "574357.82", "6279642.97", aside(Reason.NOT_DEGREES)),
        Arguments.of("S09", "", "", new Blank()),
        Arguments.of("S10", "48.5734", "", aside(Reason.HALF_A_POINT)),
        Arguments.of("S11", "n/a", "n/a", aside(Reason.NOT_A_COORDINATE)),
        Arguments.of("S12", "14.6037", "-61.0594", aside(Reason.OUTSIDE_THE_AREA)),
        Arguments.of("S13", "48°06'42\"N", "1°40'46\"W", point("48.111667", "-1.679444")),
        Arguments.of("S14", "0.6848 E", "47.3941 N", exchanged("47.3941", "0.6848")),
        Arguments.of("S15", "4.0317 E", "49.2583", exchanged("49.2583", "4.0317")),
        Arguments.of("S16", "49.1829 N", "O 0.3707", point("49.1829", "-0.3707")),
        Arguments.of("S17", "43°42'N", "7°15'E", point("43.7", "7.25")),
        Arguments.of("S18", "43 17 42 N", "0 22 12 W", point("43.295", "-0.37")),
        Arguments.of("S19", "-49.1193 N", "6.1757", aside(Reason.NOT_A_COORDINATE)),
        Arguments.of("S20", "6.1844 N", "48.6921 E", aside(Reason.OUTSIDE_THE_AREA)),
        Arguments.of("S21", "48°04′45″N", "7°21′30″E", point("48.079167", "7.358333")));
  }

  @ParameterizedTest(name = "{0}: {1} ; {2}")
  @MethodSource("theReplayedSites")
  void a_replayed_site_gives_what_the_scenario_expects(String site, String lat, String lon, Outcome expected) {
    assertThat(CoordinatePair.repair(lat, lon, FRANCE, true)).isEqualTo(expected);
  }

  static Stream<Arguments> theOtherCases() {
    return Stream.of(
        // A swapped pair is set aside unless the integrator asked for the exchange.
        Arguments.of("5.3698", "43.2965", FRANCE, false, aside(Reason.SWAPPED)),
        Arguments.of("0.6848 E", "47.3941 N", FRANCE, false, aside(Reason.SWAPPED)),
        // One letter on the wrong column is enough, on either side.
        Arguments.of("4.0317", "49.2583 N", FRANCE, true, exchanged("49.2583", "4.0317")),
        // Two letters of one axis contradict each other.
        Arguments.of("48.85 N", "2.35 S", FRANCE, true, aside(Reason.NOT_A_COORDINATE)),
        Arguments.of("2.35 E", "48.85 W", FRANCE, true, aside(Reason.NOT_A_COORDINATE)),
        // A letter on the right column is never contradicted, one letter or two.
        Arguments.of("5.3698 N", "43.2965", FRANCE, true, aside(Reason.OUTSIDE_THE_AREA)),
        Arguments.of("5.3698", "43.2965 E", FRANCE, true, aside(Reason.OUTSIDE_THE_AREA)),
        // Exchanged by its letters, and then outside the area or out of range: said in that order.
        Arguments.of("120 E", "45 N", FRANCE, true, aside(Reason.OUTSIDE_THE_AREA)),
        Arguments.of("200 E", "45 N", FRANCE, true, aside(Reason.NOT_DEGREES)),
        // No letter, in range only the other way round, and outside the area: degrees all the same.
        Arguments.of("120.5", "14.6", FRANCE, true, aside(Reason.OUTSIDE_THE_AREA)),
        // Without a declared area: the range alone speaks.
        Arguments.of("0", "0", Box.WORLD, true, point("0", "0")),
        Arguments.of("5.3698", "43.2965", Box.WORLD, true, point("5.3698", "43.2965")),
        Arguments.of("120.5", "14.6", Box.WORLD, true, exchanged("14.6", "120.5")),
        Arguments.of("95", "200", Box.WORLD, true, aside(Reason.NOT_DEGREES)),
        // The bounds of the range and of the area belong to them.
        Arguments.of("90", "-180", Box.WORLD, true, point("90", "-180")),
        Arguments.of("41.0", "10.0", FRANCE, true, point("41", "10")),
        // Blank is no text, or white space; half a point is one of each.
        Arguments.of(null, null, FRANCE, true, new Blank()),
        Arguments.of("  ", null, FRANCE, true, new Blank()),
        Arguments.of(null, "4.83", FRANCE, true, aside(Reason.HALF_A_POINT)),
        // A no-break space is what a spreadsheet leaves in an empty cell: blank, not a coordinate.
        Arguments.of("\u00A0", " \u202F", FRANCE, true, new Blank()),
        Arguments.of("\u00A0", "4.83", FRANCE, true, aside(Reason.HALF_A_POINT)));
  }

  @ParameterizedTest(name = "{0} ; {1} within {2}, fix {3}")
  @MethodSource("theOtherCases")
  void the_rules_one_at_a_time(String lat, String lon, Box within, boolean fix, Outcome expected) {
    assertThat(CoordinatePair.repair(lat, lon, within, fix)).isEqualTo(expected);
  }

  @Test
  void a_number_is_written_without_padding_zeros_and_never_in_scientific_notation() {
    assertThat(CoordinatePair.repair("-90", "100", Box.WORLD, true))
        .isInstanceOfSatisfying(Point.class, p -> assertThat(p.lon()).hasToString("100"));
  }

  @Test
  void each_reason_reads_as_the_replayed_script_wrote_it() {
    assertThat(Stream.of(Reason.values()).map(Reason::text)).containsExactly(
        "half a point", "not a coordinate", "not degrees", "outside the declared area", "swapped");
  }
}

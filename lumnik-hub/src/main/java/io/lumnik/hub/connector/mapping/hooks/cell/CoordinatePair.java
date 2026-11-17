package io.lumnik.hub.connector.mapping.hooks.cell;

import io.lumnik.hub.connector.mapping.hooks.cell.Coordinate.Axis;
import io.lumnik.hub.connector.mapping.hooks.cell.Coordinate.Reading;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Two cells read as one GPS point, exchanged when swapped, or set aside with a reason (#1966). Nothing is guessed:
 * hemisphere letters decide the order first and are never contradicted; without a letter, only the declared area can
 * show a swapped pair — it fits the other way round, and only that way.
 */
final class CoordinatePair {

  /** Where the points are: a pair outside it is not trusted. Bounds included. */
  record Box(double south, double west, double north, double east) {

    /** Every latitude and longitude there is: what "no area declared" means. */
    static final Box WORLD = new Box(-90, -180, 90, 180);

    boolean holds(double lat, double lon) {
      return lat >= south && lat <= north && lon >= west && lon <= east;
    }
  }

  enum Reason {
    HALF_A_POINT("half a point"),
    NOT_A_COORDINATE("not a coordinate"),
    NOT_DEGREES("not degrees"),
    OUTSIDE_THE_AREA("outside the declared area"),
    SWAPPED("swapped");

    private final String text;

    Reason(String text) {
      this.text = text;
    }

    String text() {
      return text;
    }
  }

  sealed interface Outcome permits Blank, Point, SetAside {}

  /** No point was given: nothing to repair, nothing to count. */
  record Blank() implements Outcome {}

  /** A usable point, six decimals at most; {@code exchanged} when the pair arrived swapped. */
  record Point(BigDecimal lat, BigDecimal lon, boolean exchanged) implements Outcome {}

  record SetAside(Reason reason) implements Outcome {}

  private static final int DECIMALS = 6;

  private CoordinatePair() {}

  /** {@code within}: the declared area, or {@link Box#WORLD} when none is — the range alone speaks then. */
  static Outcome repair(String rawLat, String rawLon, Box within, boolean fixSwapped) {
    boolean noLat = Coordinate.blank(rawLat);
    boolean noLon = Coordinate.blank(rawLon);
    if (noLat && noLon) return new Blank();
    if (noLat || noLon) return new SetAside(Reason.HALF_A_POINT);
    Optional<Reading> readLat = Coordinate.read(rawLat);
    Optional<Reading> readLon = Coordinate.read(rawLon);
    if (readLat.isEmpty() || readLon.isEmpty()) return new SetAside(Reason.NOT_A_COORDINATE);
    Reading first = readLat.get();
    Reading second = readLon.get();

    // What the letters say: 2.35 E under lat, or 48.85 N under lon, is a swapped pair.
    boolean saysSwapped = first.axis() == Axis.LON || second.axis() == Axis.LAT;
    boolean saysAsWritten = first.axis() == Axis.LAT || second.axis() == Axis.LON;
    if (saysSwapped && saysAsWritten) return new SetAside(Reason.NOT_A_COORDINATE);
    boolean lettered = saysSwapped || saysAsWritten;
    double lat = saysSwapped ? second.degrees() : first.degrees();
    double lon = saysSwapped ? first.degrees() : second.degrees();

    if (within.holds(lat, lon)) return saysSwapped ? exchanged(lat, lon, fixSwapped) : point(lat, lon, false);
    if (!lettered && within.holds(lon, lat)) return exchanged(lon, lat, fixSwapped);
    boolean degrees = Box.WORLD.holds(lat, lon) || (!lettered && Box.WORLD.holds(lon, lat));
    return new SetAside(degrees ? Reason.OUTSIDE_THE_AREA : Reason.NOT_DEGREES);
  }

  private static Outcome exchanged(double lat, double lon, boolean fixSwapped) {
    return fixSwapped ? point(lat, lon, true) : new SetAside(Reason.SWAPPED);
  }

  private static Outcome point(double lat, double lon, boolean exchanged) {
    return new Point(rounded(lat), rounded(lon), exchanged);
  }

  /** Six decimals at most, written as the number reads: {@code 45.7302}, {@code 100} — no padding zero, no exponent. */
  private static BigDecimal rounded(double degrees) {
    BigDecimal value = BigDecimal.valueOf(degrees).setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros();
    return value.scale() < 0 ? value.setScale(0) : value;
  }
}

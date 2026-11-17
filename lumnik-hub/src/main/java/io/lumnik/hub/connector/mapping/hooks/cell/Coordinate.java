package io.lumnik.hub.connector.mapping.hooks.cell;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One cell read as signed degrees, with the axis its hemisphere letter names (#1966). The accepted spellings are a
 * closed list: a decimal number with a point or a comma, signed or carrying a hemisphere letter before or after it —
 * never both, a sign times a letter would be a guess; degrees, minutes and optional seconds with a hemisphere letter.
 * Anything else is not a coordinate.
 */
final class Coordinate {

  enum Axis { LAT, LON }

  /** A hemisphere letter: the axis it names and the sign it gives. {@code O} is Ouest, as a French export writes West. */
  private enum Hemisphere {
    N(Axis.LAT, 1), S(Axis.LAT, -1), E(Axis.LON, 1), W(Axis.LON, -1), O(Axis.LON, -1);

    private final Axis axis;
    private final int sign;

    Hemisphere(Axis axis, int sign) {
      this.axis = axis;
      this.sign = sign;
    }

    static Optional<Hemisphere> at(String text, int index) {
      if (index < 0 || index >= text.length()) return Optional.empty();
      return switch (text.charAt(index)) {
        case 'N' -> Optional.of(N);
        case 'S' -> Optional.of(S);
        case 'E' -> Optional.of(E);
        case 'W' -> Optional.of(W);
        case 'O' -> Optional.of(O);
        default -> Optional.empty();
      };
    }
  }

  /** {@code axis} is what the hemisphere letter names; null when the cell carries none. */
  record Reading(double degrees, Axis axis) {}

  private static final String NUMBER = "\\d+(?:[.,]\\d+)?";
  private static final Pattern SIGNED = Pattern.compile("([+-]?)(" + NUMBER + ")");
  /** {@code 48°50'12"}, {@code 43°42'}: each mark in its place — {@code 30'} alone is minutes, not degrees. */
  private static final Pattern MARKED =
      Pattern.compile("(\\d{1,3})\\s*°\\s*(\\d{1,2})\\s*'\\s*(?:(" + NUMBER + ")\\s*\"?)?");
  /** {@code 43 17 42}, {@code 43 17}: the same, written with spaces only. */
  private static final Pattern SPACED = Pattern.compile("(\\d{1,3})\\s+(\\d{1,2})(?:\\s+(" + NUMBER + "))?");

  private Coordinate() {}

  static Optional<Reading> read(String raw) {
    String text = plain(raw);
    Optional<Hemisphere> before = Hemisphere.at(text, 0);
    Optional<Hemisphere> after = Hemisphere.at(text, text.length() - 1);
    if (before.isPresent() && after.isPresent()) return Optional.empty();
    Optional<Hemisphere> letter = before.or(() -> after);
    String body = text.substring(before.isPresent() ? 1 : 0, text.length() - (after.isPresent() ? 1 : 0)).strip();

    OptionalDouble decimal = decimal(body, letter.isEmpty());
    OptionalDouble magnitude = decimal.isPresent() || letter.isEmpty() ? decimal : sexagesimal(body);
    if (magnitude.isEmpty()) return Optional.empty();
    return Optional.of(letter
        .map(h -> new Reading(h.sign * magnitude.getAsDouble(), h.axis))
        .orElseGet(() -> new Reading(magnitude.getAsDouble(), null)));
  }

  /** Nothing to read: no text, or only white space, a no-break space included. */
  static boolean blank(String raw) {
    return raw == null || plain(raw).isEmpty();
  }

  /**
   * Upper case, and what a spreadsheet leaves read as the plain character: typographic marks, and the no-break
   * spaces a French locale puts before a unit (U+00A0, U+2007, U+202F).
   */
  private static String plain(String raw) {
    return raw.toUpperCase(Locale.ROOT)
        .replace('’', '\'').replace('′', '\'').replace('”', '"').replace('″', '"')
        .replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u202F', ' ')
        .strip();
  }

  private static OptionalDouble decimal(String text, boolean signAllowed) {
    Matcher m = SIGNED.matcher(text);
    if (!m.matches() || (!signAllowed && !m.group(1).isEmpty())) return OptionalDouble.empty();
    double value = Double.parseDouble(m.group(2).replace(',', '.'));
    return OptionalDouble.of("-".equals(m.group(1)) ? -value : value);
  }

  /** Degrees and minutes, seconds optional; sixty minutes or sixty seconds are not read. */
  private static OptionalDouble sexagesimal(String body) {
    Matcher m = MARKED.matcher(body);
    if (!m.matches()) m = SPACED.matcher(body);
    if (!m.matches()) return OptionalDouble.empty();
    int minutes = Integer.parseInt(m.group(2));
    double seconds = m.group(3) == null ? 0 : Double.parseDouble(m.group(3).replace(',', '.'));
    if (minutes >= 60 || seconds >= 60) return OptionalDouble.empty();
    return OptionalDouble.of(Integer.parseInt(m.group(1)) + minutes / 60.0 + seconds / 3600.0);
  }
}

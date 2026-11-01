package io.lumnik.hub.connector.mapping.writer;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A number with a unit suffix, read in its base unit (#1618): Kubernetes' quantity notation, which is the SI and
 * binary one many APIs share. {@code 100m} is 0.1, {@code 64Mi} is 67 108 864, {@code 99827146n} is 0.099827146,
 * {@code 1e3} is 1000. The suffix is case-sensitive, as the notation is ({@code m} milli, {@code M} mega).
 */
public final class Quantity {
  private Quantity() {}

  /** Bounded digits and exponent: a value never expands into a huge number (#1090). */
  private static final Pattern QUANTITY =
      Pattern.compile("([+-]?(?:\\d{1,30}(?:\\.\\d{0,30})?|\\.\\d{1,30}))(?:[eE]([+-]?\\d{1,2})|(Ki|Mi|Gi|Ti|Pi|Ei|[numkMGTPE]))?");

  private static final Map<String, BigDecimal> SUFFIX = Map.ofEntries(
      Map.entry("n", BigDecimal.ONE.movePointLeft(9)), Map.entry("u", BigDecimal.ONE.movePointLeft(6)),
      Map.entry("m", BigDecimal.ONE.movePointLeft(3)), Map.entry("k", BigDecimal.ONE.movePointRight(3)),
      Map.entry("M", BigDecimal.ONE.movePointRight(6)), Map.entry("G", BigDecimal.ONE.movePointRight(9)),
      Map.entry("T", BigDecimal.ONE.movePointRight(12)), Map.entry("P", BigDecimal.ONE.movePointRight(15)),
      Map.entry("E", BigDecimal.ONE.movePointRight(18)),
      Map.entry("Ki", BigDecimal.valueOf(1L << 10)), Map.entry("Mi", BigDecimal.valueOf(1L << 20)),
      Map.entry("Gi", BigDecimal.valueOf(1L << 30)), Map.entry("Ti", BigDecimal.valueOf(1L << 40)),
      Map.entry("Pi", BigDecimal.valueOf(1L << 50)), Map.entry("Ei", BigDecimal.valueOf(1L << 60)));

  /** The quantity in its base unit; anything else is refused without quoting it (#1090: a message can reach a run). */
  public static BigDecimal parse(String text) {
    Matcher m = QUANTITY.matcher(text.strip());
    if (!m.matches()) throw new IllegalArgumentException("not a quantity: a number with an optional unit suffix");
    BigDecimal number = new BigDecimal(m.group(1));
    if (m.group(2) != null) {
      int exponent = Integer.parseInt(m.group(2));
      if (Math.abs(exponent) > 18) throw new IllegalArgumentException("not a quantity: exponent beyond 18");
      return number.scaleByPowerOfTen(exponent).stripTrailingZeros();
    }
    return (m.group(3) == null ? number : number.multiply(SUFFIX.get(m.group(3)))).stripTrailingZeros();
  }
}

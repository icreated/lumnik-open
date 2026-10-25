package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recovers text where UTF-8 bytes were mistakenly decoded as Latin-1/Windows-1252.
 * Example: {@code BÃ©nÃ©dict} -> {@code Bénédict}.
 *
 * <p><b>The model for a cell hook that cannot always do what it declares.</b> Three moves, and
 * the order is the point:
 * <ol>
 *   <li><b>Detect</b> that the declared transformation cannot be honoured on this value. A hook
 *       that cannot tell it has failed is beyond the reach of any later policy — which is why
 *       this comes first, and why it is the half that takes the thinking.</li>
 *   <li><b>Decline</b> — return the source value EXACTLY as it was, never a lossy approximation
 *       of it. A partly-repaired value is indistinguishable downstream from a correct one.</li>
 *   <li><b>Count</b> — {@code cellErrors}, so the decline reaches the operator as
 *       {@code cell_errors} on the run rather than as nothing at all.</li>
 * </ol>
 *
 * <p>A value the hook was never asked to touch is <em>not</em> a decline and must not be counted:
 * a counter that ticks on every clean run stops meaning anything.
 *
 * <p>Note for whoever copies this: the six hooks that counted before this one all NULL the cell
 * when they count. This one counts while keeping the value, so {@code cell_errors} now covers two
 * shapes — emptied, or left as it arrived. Anything that renders that counter must say both.
 */
@ApplicationScoped
@HookId("fix-mojibake")
public class FixMojibakeHook implements CellHook {

  /**
   * A UTF-8 lead byte followed by its continuation byte(s) — the signature of text written as UTF-8 and read back
   * as Latin-1 or Windows-1252 (the continuation then shows as a cp1252 sign, #1419). Compiled once: {@code String.matches} rebuilds the
   * pattern on every call, and this one runs per value of every row (measured 139 ns -> 54 ns).
   *
   * <p>Matched with {@code find}, not with a {@code .*}-wrapped {@code matches}: a CSV cell can
   * hold newlines (the reader supports quoted embedded ones — a postal address is the everyday
   * case) and {@code .} does not cross a line terminator, so a wrapped whole-value match never
   * even looked for mojibake past the first newline. Searching for the signature is also cheaper,
   * measured 54 ns -> 31 ns, but the reason is correctness.
   */
  private static final Pattern MOJIBAKE;

  /**
   * #1419: the byte each Windows-1252 sign stands for ({@code ‰} → {@code 0x89}, {@code €} → {@code 0x80}…). UTF-8
   * read as cp1252 — every Windows export — puts a continuation byte on these signs, above U+00FF, where the
   * Latin-1 reading never saw it. Built once from the JDK's own cp1252 table.
   */
  private static final Map<Character, Byte> CP1252_BYTE = new HashMap<>();

  static {
    Charset cp1252 = Charset.forName("windows-1252");
    StringBuilder signs = new StringBuilder();
    for (int b = 0x80; b <= 0x9F; b++) {
      char c = new String(new byte[] {(byte) b}, cp1252).charAt(0);
      if (c > 0xFF && c != '\uFFFD') {
        CP1252_BYTE.put(c, (byte) b);
        signs.append(c);
      }
    }
    // A continuation byte, read either way: Latin-1 (0x80-0xBF itself) or a cp1252 sign. The leads stay narrow on
    // purpose — Ã Â (most accented letters), Å (Œ œ Š š Ÿ), and â before TWO continuations (’ “ – € …) — so a clean
    // French word is never taken for mojibake. Ä (Central European, Turkish) is left out on purpose: clean German
    // "Ä…" is C4 85, which decodes to a valid "ą" — a silent false repair no U+FFFD check would catch.
    // No cp1252 sign is a character-class metacharacter (] \\ ^ -), so they go in as they are.
    String cont = "[\\x80-\\xbf" + signs + "]";
    MOJIBAKE = Pattern.compile("[\\xc3\\xc2\\xc5]" + cont + "|\\xe2" + cont + cont);
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, transform(str, s));
    }
  }

  private String transform(String input, MappingState s) {
    // No mojibake here — nothing was asked of this value.
    if (!MOJIBAKE.matcher(input).find()) return input;

    // DETECT. The repair reads the text's characters back as bytes: its Latin-1 byte, or its cp1252 byte for a
    // Windows-1252 sign (#1419). A character with neither has no byte to return to; recovering anyway would hand
    // back a value missing exactly the characters the hook was never asked about — the Japanese, Cyrillic or emoji
    // sitting next to the mojibake.
    byte[] bytes = new byte[input.length()];
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      Byte b = c <= 0xFF ? Byte.valueOf((byte) c) : CP1252_BYTE.get(c);
      if (b == null) return decline(input, s);
      bytes[i] = b;
    }

    String recovered = new String(bytes, StandardCharsets.UTF_8);
    // The bytes looked like mojibake but do not form valid UTF-8 after all.
    if (recovered.indexOf('�') >= 0) return decline(input, s);
    return recovered;
  }

  private String decline(String input, MappingState s) {
    s.counters().cellErrors().incrementAndGet();
    return input;
  }

  @Override
  public Set<String> params() {
    return Set.of("columns");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumns(params);
  }
}

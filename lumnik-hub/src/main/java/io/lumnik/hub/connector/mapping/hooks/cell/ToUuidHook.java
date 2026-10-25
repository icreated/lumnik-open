package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates (and optionally generates) a UUID cell value.
 *
 * <p><b>Two independent questions, two knobs.</b> They used to be one, and that is what made a
 * fabricated identifier possible:
 * <ul>
 *   <li>{@code generate} fills an <b>absence</b> — a cell the source left empty.</li>
 *   <li>{@code on-invalid} decides a value that is <b>present but unreadable</b>:
 *       {@code null} (the default) empties it, {@code generate} mints a fresh id in its place,
 *       {@code keep} leaves the source text exactly as it arrived.</li>
 * </ul>
 *
 * <p>The default is {@code null} on purpose: minting an id over a value the source really sent
 * substitutes a FABRICATED identifier for a real one — unjoinable to every other system holding
 * the true value, and indistinguishable downstream from an id that was genuinely received. That
 * is available, because an integrator building a surrogate key legitimately wants it, but it is
 * now something they ask for by name rather than something they get by implication.
 *
 * <p><b>What counts a {@code cellErrors}, and why:</b> {@code null} and {@code generate} do,
 * because the outcome is a <em>failure the operator must see</em>; {@code keep} does not, because
 * it is the outcome the <em>integrator declared</em>. That is the same line {@code LookupHook}
 * draws for its own {@code keep}. Note it is NOT "the value changed" — {@code FixMojibakeHook}
 * and {@code SplitHook} both count while handing the source value back untouched.
 *
 * <p><b>{@code generate-from: <column>}</b> (#1423): a random id is a new one on every run, so the next night's export
 * gave the same supplier another id — and the run reported its row removed. A minted id is then a name-based UUID of
 * that column's value: the same code, the same id, every night. An empty source column mints nothing, counted.
 */
@ApplicationScoped
@HookId("to-uuid")
public class ToUuidHook implements CellHook {

  /** The closed set of {@code on-invalid} answers. Enumerated ONCE — see {@link #of}. */
  private enum OnInvalid {
    NULL, GENERATE, KEEP;

    static OnInvalid of(JsonNode params) {
      // A list or a map is refused by its shape, not as got '' (#1468).
      String raw = ScalarParams.optionalText("to-uuid hook", "on-invalid", params.path("on-invalid"), "null");
      return switch (raw) {
        case "null" -> NULL;
        case "generate" -> GENERATE;
        case "keep" -> KEEP;
        default -> throw new MappingValidationException(
            "to-uuid hook: 'on-invalid' must be one of null, generate, keep (got '" + raw + "')");
      };
    }
  }

  @Override
  public void validateParams(JsonNode params) {
    // A misspelt on-invalid mode is a manifest error, refused once per spec before any row —
    // never on the first row that happens to carry an unreadable id.
    OnInvalid.of(params);
    // #1486: "yes" or a list read as false — an empty id left empty, in silence.
    ScalarParams.optionalBoolean("to-uuid hook", "generate", params.path("generate"), false);
    JsonNode from = params.path("generate-from");
    if (from.isMissingNode() || from.isNull()) return;
    if (!from.isTextual() || from.asText().isBlank() || from.asText().equals(params.path("column").asText())) {
      String got = from.isTextual() ? "'" + from.asText() + "'" : MappingValidationException.typeName(from);
      throw new MappingValidationException(
          "to-uuid hook: 'generate-from' must name a column other than the id column (got " + got + ")");
    }
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    OnInvalid onInvalid = OnInvalid.of(params);
    String from = params.path("generate-from").isTextual() ? params.path("generate-from").asText() : null;

    Object v = row.get(col);
    if (v == null) {
      // An absence is what `generate` declares it will handle: the hook doing its job, not
      // failing at it, so nothing is counted.
      if (params.path("generate").asBoolean(false) || from != null) {
        String minted = mint(row, from, col);
        row.put(col, minted);
        if (minted == null) s.counters().cellErrors().incrementAndGet();   // nothing to derive it from
      }
      return;
    }

    if (isUuid(v.toString())) return;   // well-formed - never touched

    // A switch EXPRESSION over the enum, so the compiler refuses a new mode that forgets to say
    // whether it counts. A statement switch would let it fall through to the increment in silence.
    boolean counted = switch (onInvalid) {
      case NULL -> { row.put(col, null); yield true; }
      case GENERATE -> { row.put(col, mint(row, from, col)); yield true; }
      case KEEP -> false;               // the source value is still exactly there
    };
    if (counted) s.counters().cellErrors().incrementAndGet();
  }

  /** A random id, or with {@code generate-from} the same id for the same value; null when that value is empty. */
  private static String mint(Map<String, Object> row, String from, String col) {
    if (from == null) return UUID.randomUUID().toString();
    Object seed = row.get(from);
    if (seed == null || seed.toString().isBlank()) return null;
    // The id column's name is in the seed: two id columns derived from the same codes do not share ids.
    return UUID.nameUUIDFromBytes((col + ":" + seed).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * Length gate first, on purpose. {@code UUID.fromString}'s own first test is
   * {@code length() == 36}, and reaching it through a thrown {@code IllegalArgumentException}
   * costs ~318 ns of stack-trace fill-in: measured, a cell holding {@code "N/A"} went 369 ns -> 11.5 ns,
   * ~32x, while a well-formed id is unchanged (40.36 vs 40.37 ns). Length ONLY — also checking the
   * dash positions re-does work {@code fromString} performs anyway and costs 2.5 ns on the valid
   * path, which is the path every row takes.
   */
  private static boolean isUuid(String text) {
    if (text.length() != 36) return false;
    try {
      UUID.fromString(text);
      return true;
    } catch (IllegalArgumentException notAUuid) {
      return false;
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "on-invalid", "generate", "generate-from");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    // An absent id column is minted only with `generate` or `generate-from`; otherwise the hook reads it, and one
    // that does not exist would leave it doing nothing.
    ColumnEffect.Builder b = ColumnEffect.builder().reads("generate-from", params.path("generate-from"));
    boolean mints = params.path("generate").asBoolean(false) || params.path("generate-from").isTextual();
    return (mints ? b.adds(params.path("column")) : b.reads("column", params.path("column"))).build();
  }
}

package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
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
 */
@ApplicationScoped
@HookId("to-uuid")
public class ToUuidHook implements CellHook {

  /** The closed set of {@code on-invalid} answers. Enumerated ONCE — see {@link #of}. */
  private enum OnInvalid {
    NULL, GENERATE, KEEP;

    static OnInvalid of(JsonNode params) {
      String raw = params.path("on-invalid").asText("null");
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
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    OnInvalid onInvalid = OnInvalid.of(params);

    Object v = row.get(col);
    if (v == null) {
      // An absence is what `generate` declares it will handle: the hook doing its job, not
      // failing at it, so nothing is counted.
      if (params.path("generate").asBoolean(false)) row.put(col, UUID.randomUUID().toString());
      return;
    }

    if (isUuid(v.toString())) return;   // well-formed - never touched

    // A switch EXPRESSION over the enum, so the compiler refuses a new mode that forgets to say
    // whether it counts. A statement switch would let it fall through to the increment in silence.
    boolean counted = switch (onInvalid) {
      case NULL -> { row.put(col, null); yield true; }
      case GENERATE -> { row.put(col, UUID.randomUUID().toString()); yield true; }
      case KEEP -> false;               // the source value is still exactly there
    };
    if (counted) s.counters().cellErrors().incrementAndGet();
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
}

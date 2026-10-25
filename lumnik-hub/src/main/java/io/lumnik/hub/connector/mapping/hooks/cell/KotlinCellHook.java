package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.hook.KotlinScriptHost;
import io.lumnik.hub.hook.ScriptFailures;
import io.lumnik.hub.hook.kts.KtsScriptRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.script.SimpleBindings;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Escape hatch cell hook: runs an arbitrary Kotlin .kts script over the current row.
 *
 * <p>YAML config:
 * <pre>{@code
 * - kotlin:
 *     script: hooks:cleanup-phone.kts   # or classpath:mapping/cleanup-phone.kts, built into the hub
 *     context:                    # optional, injected as `context` binding
 *       defaultCountry: FR
 * }</pre>
 *
 * <p>The script receives three bindings accessible via {@code bindings["key"]}:
 * <ul>
 *   <li>{@code cells} — {@code MutableMap<String, Any?>}, the current row (script can mutate)</li>
 *   <li>{@code context} — {@code Map<String, Any?>}, params.context values with their YAML types (#1440): text,
 *       number, boolean, list, map — {@code (context["lead_days"] as Number).toInt()}. Read-only by convention; each
 *       row gets its own copy.</li>
 *   <li>{@code unreadable} — a {@code Runnable} (#1432): {@code (bindings["unreadable"] as Runnable).run()} counts one
 *       value the script could not read, as a built-in hook counts one, and {@code on-error.default} decides its row.
 *       A script that throws is a broken script, not an unreadable value: the run fails, naming the script, the
 *       exception's kind and the script's line — not its message, which can quote a cell.</li>
 * </ul>
 *
 * <p>Example .kts:
 * <pre>{@code
 * @Suppress("UNCHECKED_CAST")
 * val cells = bindings["cells"] as MutableMap<String, Any?>
 * cells["phone"] = (cells["phone"] as? String)?.uppercase()
 * }</pre>
 */
@ApplicationScoped
@HookId("kotlin")
public class KotlinCellHook implements CellHook {

  private static final String CONTEXT = "context";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  @Inject KotlinScriptHost scriptHost;

  @Override
  public void validateParams(JsonNode params) {
    // Once per spec, before any row, and at apply. Missing/null/blank all count as absent — a bare
    // `script:` is null, whose asText() is the literal "null", not "".
    JsonNode script = params.path("script");
    if (script.isMissingNode() || script.isNull() || script.asText().isBlank()) {
      throw new MappingValidationException("kotlin hook: 'script' is required");
    }
    JsonNode context = params.path(CONTEXT);
    if (!context.isMissingNode() && !context.isNull() && !context.isObject()) {
      throw new MappingValidationException("kotlin hook: 'context' must be a map of name to value (got "
          + MappingValidationException.typeName(context) + ")");
    }
    // on-error reads a hook's `column:` (#1432); a script can write several cells, so it declares none and
    // on-error's `default` decides. Refused rather than ignored: ignored, it would quietly switch the policy.
    if (params.has("column")) {
      throw new MappingValidationException("kotlin hook: 'column' is not a parameter of a script — "
          + "on-error's default decides a value it declares unreadable");
    }
    try {
      scriptHost.check(script.asText());   // reads a hooks: file again, so an edit is taken up per chunk
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new MappingValidationException("kotlin hook: " + e.getMessage(), e);
    }
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    JsonNode declared = params.path(CONTEXT);
    Map<String, Object> context = declared.isObject() ? JSON.convertValue(declared, MAP) : new HashMap<>();   // #1440

    Map<String, Object> bindings = new HashMap<>();
    bindings.put("cells", row);
    bindings.put(CONTEXT, context);
    bindings.put("unreadable", (Runnable) () -> state.counters().cellErrors().incrementAndGet());

    String reference = params.path("script").asText();
    KtsScriptRunner.Compiled script;
    try {
      // Compiled by check(), before the chunk; pinned by its first row, so every row of the chunk runs
      // the same version even when another run's check() replaces it meanwhile — this runs once per ROW.
      // The chunk records the version it pinned, so the run can say which rule produced its rows (#1430).
      script = state.pinned(reference, () -> {
        KotlinScriptHost.Loaded loaded = scriptHost.loaded(reference);
        state.scripts().add(loaded.version());
        return loaded.compiled();
      });
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new MappingValidationException("kotlin hook: " + e.getMessage(), e);
    }
    try {
      script.evaluate(new SimpleBindings(bindings));
    } catch (Exception e) {
      // The exception's kind and the script's line, never its message: toInt() quotes the cell it could not read,
      // and the message reaches the run (#1432, #1090). The cause keeps it for the hub's log.
      throw new ScriptFailedException(
          "kotlin hook " + reference + " failed: " + ScriptFailures.kindAndLine(e), e);
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("script", CONTEXT);
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.UNKNOWN_AFTER;   // a script may add, change or remove any key
  }
}

package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.hook.KotlinScriptHost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

/**
 * Escape hatch cell hook: runs an arbitrary Kotlin .kts script over the current row.
 *
 * <p>YAML config:
 * <pre>{@code
 * - kotlin:
 *     script: classpath:mapping/cleanup-phone.kts
 *     context:                    # optional, injected as `context` binding
 *       defaultCountry: FR
 * }</pre>
 *
 * <p>The script receives two bindings accessible via {@code bindings["key"]}:
 * <ul>
 *   <li>{@code cells} — {@code MutableMap<String, Any?>}, the current row (script can mutate)</li>
 *   <li>{@code context} — {@code Map<String, Any?>}, params.context values (read-only)</li>
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

  @Inject KotlinScriptHost scriptHost;

  @Override
  public void validateParams(JsonNode params) {
    // 'script' presence is manifest shape, invariant across rows — refuse once per spec, so
    // `kotlin: {}` fails before any row rather than only when a row arrives. (Script load, compile
    // and execution depend on runtime state and stay in apply.) Missing/null/blank all count as
    // absent — a bare `script:` is null, whose asText() is the literal "null", not "".
    JsonNode script = params.path("script");
    if (script.isMissingNode() || script.isNull() || script.asText().isBlank()) {
      throw new MappingValidationException("kotlin hook: 'script' is required");
    }
    classpathResource(script.asText());   // the `classpath:` shape is invariant too — refused once here
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    String resource = classpathResource(params.path("script").asText());

    Map<String, Object> context = new HashMap<>();
    if (params.has(CONTEXT)) {
      params.get(CONTEXT).fields()
          .forEachRemaining(e -> context.put(e.getKey(), e.getValue().asText()));
    }

    Map<String, Object> bindings = new HashMap<>();
    bindings.put("cells", row);
    bindings.put(CONTEXT, context);

    try {
      // Read and compiled once per path by the host — this runs once per ROW.
      scriptHost.execute(resource, bindings);
    } catch (Exception e) {
      throw new MappingValidationException("kotlin hook script failed: " + e.getMessage(), e);
    }
  }

  private static String classpathResource(String path) {
    if (path.startsWith("classpath:")) {
      String resource = path.substring("classpath:".length()).trim();
      if (resource.isEmpty()) {
        throw new MappingValidationException("kotlin hook: 'classpath:' needs a resource path, got: " + path);
      }
      return resource;
    }
    throw new MappingValidationException("only classpath: scripts supported in v1, got: " + path);
  }
}

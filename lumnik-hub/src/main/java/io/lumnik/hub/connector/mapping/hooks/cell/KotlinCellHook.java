package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.hook.KotlinScriptHost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    String scriptPath = params.path("script").asText();
    if (scriptPath.isEmpty()) {
      throw new MappingValidationException("kotlin hook: 'script' is required");
    }
    String source = loadScript(scriptPath);

    Map<String, Object> context = new HashMap<>();
    if (params.has(CONTEXT)) {
      params.get(CONTEXT).fields()
          .forEachRemaining(e -> context.put(e.getKey(), e.getValue().asText()));
    }

    Map<String, Object> bindings = new HashMap<>();
    bindings.put("cells", row);
    bindings.put(CONTEXT, context);

    try {
      scriptHost.executeSource(source, bindings);
    } catch (Exception e) {
      throw new MappingValidationException("kotlin hook script failed: " + e.getMessage(), e);
    }
  }

  private String loadScript(String path) {
    if (path.startsWith("classpath:")) {
      String resource = path.substring("classpath:".length());
      try (InputStream in = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(resource)) {
        if (in == null) {
          throw new MappingValidationException("script not found: " + path);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (MappingValidationException e) {
        throw e;
      } catch (Exception e) {
        throw new MappingValidationException("script load failed: " + e.getMessage(), e);
      }
    }
    throw new MappingValidationException("only classpath: scripts supported in v1, got: " + path);
  }
}

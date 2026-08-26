package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("require-headers")
public class RequireHeadersHook implements DatasetHook {

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    if (rows.isEmpty()) return rows;
    Set<String> present = rows.get(0).keySet();

    if (params.has("columns")) {
      for (JsonNode col : params.get("columns")) {
        if (!present.contains(col.asText())) {
          throw new MappingValidationException("required header missing: " + col.asText());
        }
      }
    }

    if (params.has("any-of")) {
      List<String> wanted = new ArrayList<>();
      boolean anyPresent = false;
      for (JsonNode col : params.get("any-of")) {
        String c = col.asText();
        wanted.add(c);
        if (present.contains(c)) {
          anyPresent = true;
          break;
        }
      }
      if (!anyPresent) {
        throw new MappingValidationException("none of required headers found: " + wanted);
      }
    }

    return rows;
  }
}

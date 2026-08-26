package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Recovers text where UTF-8 bytes were mistakenly decoded as Latin-1/Windows-1252.
 * Example: "BÃ©nÃ©dict" → "Bénédict".
 */
@ApplicationScoped
@HookId("fix-mojibake")
public class FixMojibakeHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, transform(str));
    }
  }

  private String transform(String input) {
    // Re-encode: get bytes back as Latin-1, re-decode as UTF-8.
    byte[] latin1Bytes = input.getBytes(StandardCharsets.ISO_8859_1);
    String recovered = new String(latin1Bytes, StandardCharsets.UTF_8);
    // If recovery produced replacement chars, it failed — keep original.
    if (recovered.contains("�")) return input;
    // Only apply if input has mojibake patterns (Ã followed by continuation byte range).
    if (input.matches(".*[ÃÂ][-¿].*")) return recovered;
    return input;
  }
}

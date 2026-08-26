package io.lumnik.hub.connector.mapping.writer;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ChunkTemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

  /**
   * Render a chunk template by interpolating ${field} placeholders.
   * Fields starting with "_" are resolved from the {@code systemContext} map.
   * Missing or null values become empty strings.
   */
  public String render(String template, Map<String, Object> row, Map<String, Object> systemContext) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String key = m.group(1);
      Object value = key.startsWith("_") ? systemContext.get(key) : row.get(key);
      m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
    }
    m.appendTail(out);
    return out.toString();
  }
}

package io.lumnik.hub.connector.mapping.writer;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ChunkTemplateRenderer {

  /**
   * A placeholder key is an identifier. It used to be {@code [^}]+}, which is greedy across anything
   * but a brace: one unclosed <code>${</code> then consumed every character up to the NEXT closing
   * brace — the intervening text and the well-formed placeholders inside it disappeared with it.
   */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

  /**
   * Render a chunk template by interpolating ${field} placeholders.
   *
   * <p>A field starting with {@code _} is looked up in the {@code systemContext} first, then in the
   * row. The fallback matters: an ERP export may legitimately ship a column called {@code _id}, and
   * routing every underscore key to the system context alone rendered that column as nothing in
   * every chunk — no error, no warning, just a fact missing from the corpus the chat answers from.
   * Any other field comes from the row. Missing or null values become empty strings; text that is
   * not a well-formed placeholder is left exactly as the integrator typed it.
   */
  public String render(String template, Map<String, Object> row, Map<String, Object> systemContext) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String key = m.group(1);
      Object value = resolve(key, row, systemContext);
      m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static Object resolve(String key, Map<String, Object> row, Map<String, Object> systemContext) {
    if (!key.startsWith("_")) return row.get(key);
    Object system = systemContext.get(key);
    return system != null ? system : row.get(key);
  }
}

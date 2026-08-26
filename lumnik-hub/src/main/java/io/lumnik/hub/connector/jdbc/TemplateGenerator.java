package io.lumnik.hub.connector.jdbc;

import io.lumnik.llm.spi.ChatPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class TemplateGenerator {

  private static final Logger LOG = Logger.getLogger(TemplateGenerator.class);

  @Inject Instance<ChatPort> chatPorts;

  public enum Mode { LLM_FIRST, HEURISTIC_ONLY, DUMP_1_1 }

  /** v1 entry point: dispatches by mode. LLM_FIRST attempts Ollama, falls back to heuristic. */
  public String generate(TableDescriptor td, List<Map<String, Object>> sampleRows, Mode mode) {
    return switch (mode) {
      case HEURISTIC_ONLY -> generateHeuristic(td);
      case DUMP_1_1       -> generateDump(td);
      case LLM_FIRST      -> generateWithLlmFallback(td, sampleRows);
    };
  }

  private String generateWithLlmFallback(TableDescriptor td, List<Map<String, Object>> sampleRows) {
    if (chatPorts.isUnsatisfied() || sampleRows == null || sampleRows.isEmpty()) {
      return generateHeuristic(td);
    }
    try {
      String prompt = buildLlmPrompt(td, sampleRows);
      String response = chatPorts.get().chat(prompt);
      String template = extractTemplate(response, td);
      if (template != null && !template.isBlank() && template.contains("${")) {
        return template;
      }
      LOG.warn("LLM response did not yield a usable template — falling back to heuristic");
    } catch (Exception e) {
      LOG.warnf("LLM template generation failed (%s) — falling back to heuristic", e.getMessage());
    }
    return generateHeuristic(td);
  }

  private String buildLlmPrompt(TableDescriptor td, List<Map<String, Object>> samples) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are writing a one-line text template for a RAG chunk that describes a row ");
    sb.append("from SQL table '").append(td.schema()).append(".").append(td.name()).append("'.\n");
    sb.append("Use ${column_name} interpolation. Output ONLY the template line.\n");
    sb.append("Columns: ");
    for (int i = 0; i < td.columns().size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(td.columns().get(i).name());
    }
    sb.append("\nSample rows:\n");
    int max = Math.min(samples.size(), 5);
    for (int i = 0; i < max; i++) sb.append("  ").append(samples.get(i)).append("\n");
    sb.append("Template:");
    return sb.toString();
  }

  /**
   * Extracts the first usable template line from the LLM response.
   * Strips markdown fences and leading/trailing whitespace; validates that at least
   * one referenced column exists in the table descriptor.
   */
  private String extractTemplate(String response, TableDescriptor td) {
    if (response == null) return null;
    Set<String> knownCols = new HashSet<>();
    for (ColumnDescriptor c : td.columns()) knownCols.add(c.name().toLowerCase(Locale.ROOT));

    for (String line : response.split("\\r?\\n")) {
      String stripped = line.stripLeading();
      // Skip markdown fences and blank lines
      if (stripped.startsWith("```") || stripped.isBlank()) continue;
      // Strip inline code fences if present
      final String cleaned = stripped.replace("`", "").strip();
      if (!cleaned.contains("${")) continue;
      // Verify at least one placeholder references a real column
      boolean valid = knownCols.stream().anyMatch(col -> cleaned.contains("${" + col + "}"));
      if (valid) return cleaned;
    }
    return null;
  }

  public String generateHeuristic(TableDescriptor td) {
    Map<String, ColumnDescriptor> byName = new LinkedHashMap<>();
    for (ColumnDescriptor c : td.columns()) byName.put(c.name().toLowerCase(Locale.ROOT), c);

    String pkRef = td.primaryKey().isEmpty()
        ? "" : " #${" + td.primaryKey().get(0) + "}";

    String namePart  = pickFirst(byName, List.of("name", "title"));
    String labelPart = pickFirst(byName, List.of("label"));
    String descPart  = pickFirst(byName, List.of("description", "comment"));
    String codePart  = pickFirst(byName, List.of("code"));

    // name + description → "Row ${name} #${pk}. Description: ${description}."
    if (namePart != null && descPart != null) {
      return "Row " + namePart + pkRef + ". Description: " + descPart + ".";
    }
    // code + label → "Row ${code}: ${label} #${pk}."
    if (codePart != null && labelPart != null) {
      return "Row " + codePart + ": " + labelPart + pkRef + ".";
    }
    // code + name → "Row ${code}: ${name} #${pk}."
    if (codePart != null && namePart != null) {
      return "Row " + codePart + ": " + namePart + pkRef + ".";
    }
    // name only
    if (namePart != null) {
      return "Row " + namePart + pkRef + ".";
    }
    // label only
    if (labelPart != null) {
      return "Row " + labelPart + pkRef + ".";
    }
    return generateDump(td);
  }

  public String generateDump(TableDescriptor td) {
    StringBuilder sb = new StringBuilder("Row");
    if (!td.primaryKey().isEmpty()) {
      sb.append(" #${").append(td.primaryKey().get(0)).append("}");
    }
    sb.append(": ");
    boolean first = true;
    for (ColumnDescriptor c : td.columns()) {
      if (!first) sb.append(" | ");
      sb.append(c.name()).append("=${").append(c.name()).append("}");
      first = false;
    }
    return sb.toString();
  }

  private String pickFirst(Map<String, ColumnDescriptor> cols, List<String> candidates) {
    for (String c : candidates) {
      if (cols.containsKey(c)) return "${" + cols.get(c).name() + "}";
    }
    return null;
  }
}

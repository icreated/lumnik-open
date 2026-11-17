package io.lumnik.hub.entity;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2048: a connector's scopes bound the readers of every entity over its tables. Every method that replaces them asks
 * {@link NewWriterScopes} first, or is exempt here with its reason: a {@code setTags} call, a method reference to it,
 * or SQL setting the column.
 */
class NewWriterScopesInventoryTest {

  /** {@code Class#method}, or a whole class. */
  private static final Map<String, String> EXEMPT = Map.of(
      "io.lumnik.hub.api.connector.ConnectorAdminResource#create", "a new connector writes no table yet",
      "io.lumnik.hub.source.csv.CsvIngestService", "a one-shot CSV writes ext.*, which no entity reads",
      "io.lumnik.hub.connector.domain.Connector", "the setter itself");

  private static final Pattern RETAG = Pattern.compile("\\.setTags\\(|::setTags\\b");
  /** SQL setting the column lives in a literal: judged for its class, which must ask somewhere. */
  private static final Pattern SQL_RETAG = Pattern.compile("(?i)\\bSET\\s+tags\\s*=");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\n])*\"");
  private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\([^()]*\\)\\s*(?:throws[^{]*)?$");

  @Test
  void every_scope_change_asks_the_entities_reading_its_tables() throws IOException {
    Path root = SourceTree.reactorRoot().resolve("lumnik-hub/src/main/java");
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String rel = root.relativize(f).toString();
        String cls = rel.substring(0, rel.length() - ".java".length()).replace(File.separatorChar, '.');
        if (EXEMPT.containsKey(cls)) continue;
        String raw = SourceTree.code(Files.readString(f));
        String code = STRING_LITERAL.matcher(raw).replaceAll("\"\"");
        if (SQL_RETAG.matcher(raw).find() && !code.contains("NewWriterScopes.requireRetagWithinReaders(")) {
          found.add(cls + " (SQL)");
        }
        Matcher m = RETAG.matcher(code);
        while (m.find()) {
          String[] method = enclosingMethod(code, m.start());
          if (method == null || EXEMPT.containsKey(cls + "#" + method[0])) continue;
          if (!method[1].contains("NewWriterScopes.requireRetagWithinReaders(")) found.add(cls + "#" + method[0]);
        }
      }
    }
    assertThat(found).as("A connector's scopes replaced without asking the entities reading its tables: call "
        + "NewWriterScopes.requireRetagWithinReaders in that method first, or exempt it with its reason.").isEmpty();
  }

  /** The name and body of the method holding {@code at}; the class body is depth 1. */
  private static String[] enclosingMethod(String code, int at) {
    int depth = 0;
    int bodyStart = -1;
    for (int i = 0; i < at; i++) {
      char ch = code.charAt(i);
      if (ch == '{' && ++depth == 2) bodyStart = i;
      else if (ch == '}') depth--;
    }
    if (bodyStart < 0) return null;
    int end = bodyStart;
    for (int d = 0; end < code.length(); end++) {
      if (code.charAt(end) == '{') d++;
      else if (code.charAt(end) == '}' && --d == 0) break;
    }
    String header = code.substring(Math.max(0, bodyStart - 400), bodyStart)
        .replaceAll("@[\\w.]+\\s*(?:\\([^()]*\\))?", "").stripTrailing();
    Matcher name = METHOD_NAME.matcher(header);
    return new String[] {name.find() ? name.group(1) : "?", code.substring(bodyStart, Math.min(end + 1, code.length()))};
  }
}

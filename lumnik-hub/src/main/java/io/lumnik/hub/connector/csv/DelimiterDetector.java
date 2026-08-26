package io.lumnik.hub.connector.csv;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class DelimiterDetector {
  private DelimiterDetector() {}

  private static final char[] CANDIDATES = { ',', ';', '\t', '|' };

  public static char detect(Path file, Charset encoding) {
    try (Reader rdr = Files.newBufferedReader(file, encoding)) {
      char[] buf = new char[8192];
      int n = rdr.read(buf);
      if (n <= 0) return ',';
      Map<Character, Integer> counts = new HashMap<>();
      for (char c : CANDIDATES) {
        int count = 0;
        for (int i = 0; i < n; i++) if (buf[i] == c) count++;
        counts.put(c, count);
      }
      return counts.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .filter(e -> e.getValue() > 0)
          .map(Map.Entry::getKey)
          .orElse(',');
    } catch (IOException e) {
      return ',';
    }
  }
}

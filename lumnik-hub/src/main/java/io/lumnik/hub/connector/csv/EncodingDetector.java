package io.lumnik.hub.connector.csv;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EncodingDetector {
  private EncodingDetector() {}

  public static Charset detect(Path file) {
    try (var in = new BufferedInputStream(Files.newInputStream(file))) {
      CharsetDetector detector = new CharsetDetector();
      detector.setText(in);
      CharsetMatch match = detector.detect();
      if (match == null) return StandardCharsets.UTF_8;
      try {
        return Charset.forName(match.getName());
      } catch (Exception e) {
        return StandardCharsets.UTF_8;
      }
    } catch (IOException e) {
      return StandardCharsets.UTF_8;
    }
  }
}

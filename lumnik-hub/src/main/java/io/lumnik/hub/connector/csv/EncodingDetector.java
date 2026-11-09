package io.lumnik.hub.connector.csv;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EncodingDetector {
  /** What ICU looks at: the first bytes of the file. */
  private static final int SAMPLE = 8192;

  private EncodingDetector() {}

  /** Below this, ICU itself doubts its answer. It does not separate a DOS page from Latin-1: both read 40–56. */
  private static final int DOUBTED = 25;

  /**
   * The charset chosen, ICU's confidence in it (0–100), and whether it is only a guess the integrator should confirm
   * (#1885).
   *
   * <p>{@code weak}: no declaration vouches for the charset (not UTF-8, not a byte order mark — ICU reports those at 100)
   * and the sample either holds a byte in 0x80–0x9F where an accent would sit, or ICU itself doubts its answer. ICU has no
   * recogniser for the DOS code pages (CP850, CP437): such a file is read as windows-1252 with a confidence no lower than a
   * clean Latin-1 file's, so the confidence cannot tell them apart. The bytes can: the DOS pages put é à è ç ô in 0x80–0x9F,
   * where windows-1252 has punctuation ({@code ‚ … Š ‡}). A Latin-1 or windows-1252 text with accents only above 0xC0, or
   * with typographic quotes, is not flagged.
   */
  public record Guess(Charset charset, int confidence, boolean weak) {}

  public static Guess guess(Path file) {
    byte[] sample;
    try (var in = Files.newInputStream(file)) {
      sample = in.readNBytes(SAMPLE);
    } catch (IOException e) {
      return new Guess(StandardCharsets.UTF_8, 0, false);
    }
    CharsetDetector detector = new CharsetDetector();
    detector.setText(sample);
    CharsetMatch match = detector.detect();
    if (match == null) return new Guess(StandardCharsets.UTF_8, 0, false);
    Charset charset;
    try {
      charset = Charset.forName(match.getName());
    } catch (Exception e) {
      return new Guess(StandardCharsets.UTF_8, 0, false);
    }
    boolean weak = match.getConfidence() < 100
        && !StandardCharsets.UTF_8.equals(charset)
        && holdsAByteAboveAscii(sample)
        && (match.getConfidence() < DOUBTED || holdsAnAccentOfADosPage(sample));
    return new Guess(charset, match.getConfidence(), weak);
  }

  public static Charset detect(Path file) {
    return guess(file).charset();
  }

  private static boolean holdsAByteAboveAscii(byte[] sample) {
    for (byte b : sample) if (b < 0) return true;
    return false;
  }

  /**
   * A byte in 0x80–0x9F with a letter beside it. The typographic ones (curly quotes 0x91–0x94, dashes 0x96–0x97) need a
   * letter on both sides, an accent inside a word, and 0x91–0x92 (the apostrophe of l’été) are never taken for one: a
   * windows-1252 text is not flagged for its quotes. A dash, an ellipsis or a euro sign glued to a word still is: the
   * run says it, and the integrator declares the encoding.
   */
  private static boolean holdsAnAccentOfADosPage(byte[] sample) {
    for (int i = 0; i < sample.length; i++) {
      int b = sample[i] & 0xFF;
      if (b < 0x80 || b > 0x9F || b == 0x91 || b == 0x92) continue;
      boolean before = i > 0 && isLetter(sample[i - 1] & 0xFF);
      boolean after = i + 1 < sample.length && isLetter(sample[i + 1] & 0xFF);
      boolean typographic = b == 0x93 || b == 0x94 || b == 0x96 || b == 0x97;
      if (typographic ? before && after : before || after) return true;
    }
    return false;
  }

  /** An ASCII letter, or a letter of Latin-1 (0xC0 and above, but ÷). */
  private static boolean isLetter(int b) {
    return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= 0xC0 && b != 0xF7);
  }
}

package io.lumnik.hub.entity.resolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic seeded lowercase-letter strings, shared by the #616 property-style tests ({@code
 * NormalizationOrchestratorPropertyTest}, {@code NormalizerAllowlistExclusionEvidenceTest}) so the
 * generator itself cannot drift between the two — a fixed seed, never flaky.
 */
final class RandomLetterStrings {

  private RandomLetterStrings() {}

  static List<String> of(long seed, int count) {
    Random r = new Random(seed);
    List<String> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int len = 1 + r.nextInt(24);
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < len; j++) sb.append((char) ('a' + r.nextInt(26)));
      out.add(sb.toString());
    }
    return out;
  }
}

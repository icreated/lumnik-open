package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST promote transforms TO_LOWER / TO_UPPER must be locale-independent: they normalise data
 * for storage, so the result must not depend on the server's default locale. The discriminating
 * case is the Turkish dotted/dotless I — {@code "I".toLowerCase()} yields the dotless {@code "ı"}
 * under a {@code tr} locale, but must stay ASCII {@code "i"} (which {@code Locale.ROOT} guarantees).
 * No DB needed: {@code applyTransform} is a pure function.
 */
class HybridJsonbTransformLocaleTest {

  @Test
  void to_lower_and_to_upper_are_locale_independent() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));   // Turkish: I↔ı, i↔İ under the default locale
      assertThat(HybridJsonbWriter.applyTransform("TITLE", Optional.of(Transform.TO_LOWER)))
          .isEqualTo("title");
      assertThat(HybridJsonbWriter.applyTransform("title", Optional.of(Transform.TO_UPPER)))
          .isEqualTo("TITLE");
      // The case a default-locale toLowerCase()/toUpperCase() gets wrong under `tr`:
      assertThat(HybridJsonbWriter.applyTransform("I", Optional.of(Transform.TO_LOWER)))
          .as("ASCII 'I' must lower-case to ASCII 'i', not the dotless 'ı'")
          .isEqualTo("i");
      assertThat(HybridJsonbWriter.applyTransform("i", Optional.of(Transform.TO_UPPER)))
          .as("ASCII 'i' must upper-case to ASCII 'I', not the dotted 'İ'")
          .isEqualTo("I");
    } finally {
      Locale.setDefault(previous);
    }
  }
}

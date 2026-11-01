package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1618: a number with a unit suffix — Kubernetes' notation, SI and binary — read in its base unit. */
class QuantityTest {

  @ParameterizedTest
  @CsvSource({
      "100m, 0.1", "2, 2", "1.5, 1.5", "99827146n, 0.099827146", "250u, 0.00025",
      "64Mi, 67108864", "52Ki, 53248", "1Gi, 1073741824", "1Ti, 1099511627776",
      "1k, 1000", "1M, 1000000", "1G, 1000000000", "1e3, 1000", "1.5E-3, 0.0015", "-2m, -0.002", "+3, 3",
      "' 8552Ki ', 8757248"})
  void reads_a_quantity_in_its_base_unit(String text, String base) {
    assertThat(Quantity.parse(text)).isEqualByComparingTo(new BigDecimal(base));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "m", "Mi", "12 Mi", "1mi", "1KB", "1.2.3", "abc", "1e", "1e999999999", "1e19",
      "1234567890123456789012345678901234567890"})
  void refuses_anything_else(String text) {
    // The message is fixed text: it never quotes the value (#1090).
    assertThatThrownBy(() -> Quantity.parse(text)).isInstanceOf(IllegalArgumentException.class)
        .message().isIn("not a quantity: a number with an optional unit suffix", "not a quantity: exponent beyond 18");
  }

  @ParameterizedTest
  @CsvSource({"100m, 0.1", "64Mi, 67108864"})
  void parse_quantity_is_a_promote_transform(String text, String base) {
    Object read = HybridJsonbWriter.applyTransform(text, Optional.of(Transform.PARSE_QUANTITY), "cpu");
    assertThat((BigDecimal) HybridJsonbWriter.converted(read, SqlType.DECIMAL)).isEqualByComparingTo(base);
  }
}

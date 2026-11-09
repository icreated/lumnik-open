package io.lumnik.hub.connector.csv;

import io.lumnik.hub.validator.ValidationError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #1882: a mapping that types a column the table already holds as another type is said at the apply. */
class MappingTypeDriftTest {

  private static Map<String, Object> mapping(String hook, String column) {
    return Map.of("cell", List.of(Map.of(hook, Map.of("column", column))));
  }

  @ParameterizedTest(name = "{0} on a {1} column")
  @CsvSource({
      "parse-date,    text,        true",
      "parse-date,    int8,        true",
      "parse-date,    date,        false",
      "parse-date,    timestamptz, false",
      "parse-number,  text,        true",
      "parse-number,  timestamptz, true",
      "parse-number,  numeric,     false",
      "parse-number,  int8,        false",
      "parse-boolean, text,        true",
      "parse-boolean, bool,        false"})
  void aTypedColumnHeldAsAnotherFamilyIsSaid(String hook, String held, boolean said) {
    List<ValidationError> w = MappingTypeDrift.warnings("connector.t_probe", mapping(hook, "delivered"),
        Map.of("delivered", held));
    assertThat(w).hasSize(said ? 1 : 0);
    if (said) {
      assertThat(w.get(0).severity()).isEqualTo(ValidationError.Severity.WARNING);
      assertThat(w.get(0).message()).contains("delivered", held, hook, "connector.t_probe");
      assertThat(w.get(0).recommendations()).isNotEmpty();
    }
  }

  @Test
  void aColumnTheTableDoesNotHoldYetIsNotDrift() {
    assertThat(MappingTypeDrift.warnings("connector.t_probe", mapping("parse-date", "delivered"), Map.of())).isEmpty();
  }

  @Test
  void aHookThatDoesNotTypeAColumnSaysNothing() {
    assertThat(MappingTypeDrift.warnings("connector.t_probe", mapping("lower", "delivered"),
        Map.of("delivered", "text"))).isEmpty();
  }

  @Test
  void noMappingSaysNothing() {
    assertThat(MappingTypeDrift.warnings("connector.t_probe", null, Map.of("delivered", "text"))).isEmpty();
    assertThat(MappingTypeDrift.warnings("connector.t_probe", Map.of(), Map.of("delivered", "text"))).isEmpty();
  }
}

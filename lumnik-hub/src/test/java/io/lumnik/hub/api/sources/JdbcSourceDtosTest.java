package io.lumnik.hub.api.sources;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcSourceDtosTest {

  /** A schema arrives from a flag or a JSON body; whitespace around it is never a schema name. */
  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource(value = {"null,public", "'',public", "'  ',public", "erp,erp", "' erp ',erp"}, nullValues = "null")
  void schema_or_default(String given, String expected) {
    assertEquals(expected, JdbcSourceDtos.schemaOrDefault(given));
  }
}

package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1328: {@code spec.connection.zone} is the zone a source writes its naive times in. A zone the hub
 * cannot read is refused at apply, in parse-date's words, instead of failing every run; none — missing,
 * null or blank — reads them as UTC.
 */
class ConnectionBlockZoneTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The connection block's findings on {@code zone}; a null {@code zoneJson} leaves the key out. */
  private static List<ValidationError> zoneErrors(String zoneJson) throws Exception {
    String zone = zoneJson == null ? "" : ", \"zone\": " + zoneJson;
    return ConfigDefRunner.run(ConnectionBlockValidator.configDef(), MAPPER.readTree(
            "{\"url\": \"jdbc:postgresql://h/d\", \"user\": \"u\", \"password_env\": \"PW\"" + zone + "}"),
            "spec.connection")
        .stream().filter(e -> e.path().equals("spec.connection.zone")).toList();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"\"Europe/Paris\"", "\"UTC\"", "\"+02:00\"", "\"\"", "null"})
  void a_zone_the_hub_reads_or_none_is_accepted(String zone) throws Exception {
    assertThat(zoneErrors(zone)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Paris", "Europe/Pariss", "CEST+2"})
  void a_zone_the_hub_cannot_read_is_refused_naming_it(String zone) throws Exception {
    List<ValidationError> errs = zoneErrors("\"" + zone + "\"");
    assertThat(errs).singleElement().satisfies(e -> {
      assertThat(e.severity()).isEqualTo(ValidationError.Severity.ERROR);
      assertThat(e.message()).isEqualTo("'zone' is not a time zone: \"" + zone + "\" (for example Europe/Paris or UTC)");
    });
  }
}

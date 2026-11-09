package io.lumnik.hub.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The zone an Entity declares (#1881): the business's day, used wherever a date meets a time. Absent, the
 * day is the UTC one, so an entity applied before it does not move.
 */
class EntityZoneTest {

  private static final String YAML = """
      kind: Entity
      name: Batch
      zone: %s
      match: [batch_no]
      sources:
        - table: t_erp
          map: { no: batch_no, received: received }
        - table: t_lims
          map: { code: batch_no, received_at: received }
      """;

  private static EntityManifest manifest(String zone) {
    return new EntityManifestParser().parse(zone == null
        ? YAML.replace("zone: %s\n", "") : YAML.formatted(zone));
  }

  /** The ERP says a day, the LIMS an instant: the column fuses as a timestamptz. */
  private static EntityViewCompiler.Sources sources() {
    Map<String, Map<String, String>> temporal = new HashMap<>();
    temporal.put("t_erp", Map.of("received", "date"));
    temporal.put("t_lims", Map.of("received_at", "timestamptz"));
    return new EntityViewCompiler.Sources(Set.of(), temporal);
  }

  @Test
  void theZoneIsRead() {
    assertThat(manifest("Europe/Paris").zone()).isEqualTo("Europe/Paris");
    assertThat(manifest(null).zone()).isNull();
  }

  @Test
  void zoneIsAHonouredRootKey() {
    assertThat(new EntityManifestParser().unknownKeys(YAML.formatted("Europe/Paris"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Nowhere/Land", "+02:00", "UTC+2", "Paris"})
  void aZoneThatIsNotARegionIsRefused(String zone) {
    assertThatThrownBy(() -> manifest(zone)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zone");
  }

  @Test
  void aDateIsMidnightInTheZone() {
    String view = String.join("\n", new EntityViewCompiler().buildStatements(manifest("Europe/Paris"), sources()));
    assertThat(view).contains("(\"received\"::timestamp AT TIME ZONE 'Europe/Paris')")
        .doesNotContain("AT TIME ZONE 'UTC'");
  }

  @Test
  void withoutAZoneADateIsMidnightUtc() {
    String view = String.join("\n", new EntityViewCompiler().buildStatements(manifest(null), sources()));
    assertThat(view).contains("(\"received\"::timestamp AT TIME ZONE 'UTC')");
  }

  @Test
  void aTimeBesideADayIsComparedAtTheZonesDay() {
    String view = String.join("\n", new EntityViewCompiler().buildStatements(manifest("Europe/Paris"), sources()));
    assertThat(view).contains("(\"received\" AT TIME ZONE 'Europe/Paris')::date");
  }
}

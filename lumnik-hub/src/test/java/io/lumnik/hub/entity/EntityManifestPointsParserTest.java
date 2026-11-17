package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1974: an Entity says which two of its columns are a point. */
class EntityManifestPointsParserTest {

  static final String BASE = """
      kind: Entity
      name: Customer
      match: [customer_no]
      sources:
        - table: t_erp_customers
          map: { customer_no: customer_no, name: name, latitude: latitude, longitude: longitude, lat2: depot_lat, lon2: depot_lon }
      """;

  private final EntityManifestParser parser = new EntityManifestParser();

  @Test
  void a_point_is_its_name_then_its_latitude_and_longitude_columns() {
    EntityManifest m = parser.parse(BASE + """
        points:
          - name: site
            lat: latitude
            lon: longitude
        """);

    assertThat(m.points()).containsExactly(new EntityManifest.Point("site", "latitude", "longitude"));
  }

  @Test
  void an_entity_without_points_has_none() {
    assertThat(parser.parse(BASE).points()).isEmpty();
  }

  @Test
  void an_unknown_key_under_a_point_is_named() {
    assertThat(parser.unknownKeys(BASE + """
        points:
          - { name: site, lat: latitude, lon: longitude, srid: 4326 }
        """)).containsExactly("points[0].srid");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      no name             | - { lat: latitude, lon: longitude }                                          | points[0].name is required
      a name not simple   | - { name: 'my site', lat: latitude, lon: longitude }                         | points[0].name must be a simple identifier
      a duplicate name    | - { name: site, lat: latitude, lon: longitude }\\n  - { name: Site, lat: depot_lat, lon: depot_lon } | points[1].name 'Site' is declared more than once
      no lat              | - { name: site, lon: longitude }                                             | points[0].lat is required
      no lon              | - { name: site, lat: latitude }                                              | points[0].lon is required
      the same column     | - { name: site, lat: latitude, lon: latitude }                               | points[0]: 'lat' and 'lon' must be two different columns
      an unknown column   | - { name: site, lat: lattitude, lon: longitude }                             | points[0].lat 'lattitude' is not a canonical column of the entity
      a reserved column   | - { name: site, lat: _entity_id, lon: longitude }                            | points[0].lat '_entity_id' is not a canonical column of the entity
      """)
  void a_malformed_point_is_refused_by_its_path(String why, String points, String message) {
    String yaml = BASE + "points:\n  " + points.replace("\\n", "\n") + "\n";

    assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
  }

  @Test
  void a_ninth_point_is_refused() {
    StringBuilder points = new StringBuilder("points:\n");
    for (int i = 1; i <= 9; i++) points.append("  - { name: p").append(i).append(", lat: latitude, lon: longitude }\n");

    assertThatThrownBy(() -> parser.parse(BASE + points)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("points: at most 8");
  }
}

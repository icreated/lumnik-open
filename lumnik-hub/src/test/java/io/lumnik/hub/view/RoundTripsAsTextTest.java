package io.lumnik.hub.view;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Which PostgreSQL types a link may filter on through {@code col::text = ?} without a warning. */
class RoundTripsAsTextTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "text", "char", "char(3)", "character", "character varying", "character varying(40)", "varchar",
      "varchar(255)", "bpchar", "integer", "int", "int2", "int4", "smallint", "bigint", "int8", "uuid",
      "boolean", "bool",
      "TEXT", "  Integer  ", "UUID"})
  void these_types_round_trip(String pgType) {
    assertThat(ViewApplyResource.roundTripsAsText(pgType)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "integer[]", "interval", "numeric(18,4)", "numeric", "timestamp", "timestamptz", "date", "jsonb",
      "double precision", "real", "bigserial", "int8range", "boolean[]", "uuid[]", "bit", "name", "xml"})
  void near_misses_and_other_types_do_not(String pgType) {
    assertThat(ViewApplyResource.roundTripsAsText(pgType)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void no_type_does_not(String pgType) {
    assertThat(ViewApplyResource.roundTripsAsText(pgType)).isFalse();
  }
}

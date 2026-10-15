package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** parse-date's `zone`: the zone a wall-clock time without an offset was written in (#1092). */
class ParseDateZoneTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final ParseDateHook hook = new ParseDateHook();

    private static JsonNode yaml(String src) throws Exception {
        return YAML.readTree(src);
    }

    private Object parse(String params, String value) throws Exception {
        JsonNode p = yaml(params);
        hook.validateParams(p);
        Map<String, Object> row = new HashMap<>(Map.of("stamp", value));
        hook.apply(row, p, new MappingState());
        return row.get("stamp");
    }

    @Test
    void a_declared_zone_pins_the_time_to_that_zone() throws Exception {
        assertThat(parse("column: stamp\nformats: ['yyyy-MM-dd HH:mm:ss']\nzone: Europe/Paris", "2026-08-02 23:30:00"))
            .isEqualTo(OffsetDateTime.parse("2026-08-02T23:30:00+02:00"));
        assertThat(parse("column: stamp\nformats: ['yyyy-MM-dd HH:mm:ss']\nzone: Europe/Paris", "2026-01-15 23:30:00"))
            .isEqualTo(OffsetDateTime.parse("2026-01-15T23:30:00+01:00"));
    }

    /** No zone: the value stays the LocalDateTime it always was, so every row hash already written
     *  stays the same; the writer binds it as UTC. */
    @Test
    void without_a_zone_the_value_is_unchanged() throws Exception {
        assertThat(parse("column: stamp\nformats: ['yyyy-MM-dd HH:mm:ss']", "2026-08-02 23:30:00"))
            .isEqualTo(LocalDateTime.parse("2026-08-02T23:30:00"));
    }

    /** Missing, null and blank all count as no zone, as KotlinCellHook counts them for `script`
     *  (Copilot, #1094). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zone:", "zone: \"\"", "zone: \"  \""})
    void an_empty_zone_is_no_zone(String zoneLine) throws Exception {
        assertThat(parse("column: stamp\nformats: ['yyyy-MM-dd HH:mm:ss']\n" + zoneLine, "2026-08-02 23:30:00"))
            .isEqualTo(LocalDateTime.parse("2026-08-02T23:30:00"));
    }

    @Test
    void a_date_without_a_time_ignores_the_zone() throws Exception {
        assertThat(parse("column: stamp\nformats: ['yyyy-MM-dd']\nzone: Europe/Paris", "2026-08-02"))
            .isEqualTo(LocalDate.parse("2026-08-02"));
    }

    /** A value an earlier hook (kotlin) already made temporal is left as it is — never read as
     *  text, missed, nulled and counted (Copilot, #1094). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("alreadyTemporal")
    void a_value_already_temporal_is_left_alone(Object value) throws Exception {
        JsonNode p = yaml("column: stamp\nformats: ['yyyy-MM-dd HH:mm:ss']\nzone: Europe/Paris");
        Map<String, Object> row = new HashMap<>(Map.of("stamp", value));
        MappingState state = new MappingState();
        hook.apply(row, p, state);
        assertThat(row.get("stamp")).isSameAs(value);
        assertThat(state.counters().cellErrors().get()).isZero();
    }

    static Stream<Object> alreadyTemporal() {
        return Stream.of(Instant.parse("2026-08-02T21:30:00Z"),
            ZonedDateTime.parse("2026-08-02T23:30:00+02:00[Europe/Paris]"),
            OffsetDateTime.parse("2026-08-02T23:30:00+02:00"),
            LocalDateTime.parse("2026-08-02T23:30:00"), LocalDate.parse("2026-08-02"),
            Timestamp.from(Instant.parse("2026-08-02T21:30:00Z")));
    }

    @Test
    void an_unknown_zone_is_refused_before_the_first_row() {
        assertThatThrownBy(() -> hook.validateParams(yaml("column: stamp\nformats: ['yyyy-MM-dd HH:mm']\nzone: Europe/Pariss")))
            .isInstanceOf(MappingValidationException.class)
            .hasMessage("parse-date hook: 'zone' is not a time zone: \"Europe/Pariss\" (for example Europe/Paris or UTC)");
    }
}

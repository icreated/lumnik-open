package io.lumnik.hub.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #1809: {@code inRange} read the value with {@code asInt()}, so {@code 4294967297} wrapped to {@code 1} and passed
 * {@code inRange(1, 100)}, {@code 1.5} read as {@code 1}, and {@code "abc"} read as {@code 0}. A value is in range
 * only when it is a whole number — an integral JSON number that fits a long, or the digits of one — inside the bounds.
 */
class ConfigDefValidatorsInRangeTest {

    private static final String REFUSAL = "must be a whole number between 1 and 100";

    private final ObjectMapper mapper = new ObjectMapper();

    private Optional<String> check(String json, long min, long max) throws Exception {
        JsonNode value = mapper.readTree(json);
        return ConfigDefValidators.inRange(min, max).apply(value);
    }

    @ParameterizedTest(name = "{0} is in [1, 100]")
    @ValueSource(strings = {"1", "100", "50", "\"50\"", "\" 7 \""})
    void a_whole_number_inside_the_bounds_passes(String json) throws Exception {
        assertThat(check(json, 1, 100)).isEmpty();
    }

    @ParameterizedTest(name = "{0} is refused by [1, 100]")
    @ValueSource(strings = {
        "0", "101", "-1",                          // just outside each bound
        "4294967297", "9223372036854775808",       // wraps to 1 as an int; overflows a long
        "18446744073709551617",                    // 2^64 + 1: wraps to 1 even as a long
        "1.5", "50.0",                             // not a whole number
        "\"abc\"", "\"\"", "\"1.5\"", "true", "null", "[1]",
        "\"+7\"", "\"\u0667\""})              // a sign JSON has no number for; a digit that is not ASCII
    void anything_else_is_refused(String json) throws Exception {
        assertThat(check(json, 1, 100)).contains(REFUSAL);
    }

    @ParameterizedTest(name = "{0} is in the long bounds")
    @ValueSource(strings = {"2147483648", "4294967297"})
    void the_bounds_are_longs_so_a_long_field_can_use_them(String json) throws Exception {
        assertThat(check(json, 1, 5_000_000_000L)).isEmpty();
    }
}

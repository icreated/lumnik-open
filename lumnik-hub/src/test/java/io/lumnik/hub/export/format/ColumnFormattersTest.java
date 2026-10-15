package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The four formatters #1090 ships: what each writes, what it refuses at apply, what fails a run. */
class ColumnFormattersTest {

    private static final ObjectMapper YAML = new YAMLMapper();

    static JsonNode params(String flowYaml) {
        try {
            return YAML.readTree(flowYaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    class Lookup {
        private final ColumnFormatterFactory factory = new LookupFormatterFactory();

        @ParameterizedTest(name = "{0} with {1} → {2}")
        @CsvSource(delimiter = '|', textBlock = """
            ACTIVE | '{table: {ACTIVE: A, CLOSED: C}}'                        | A
            OTHER  | '{table: {ACTIVE: A}}'                                   | OTHER
            OTHER  | '{table: {ACTIVE: A}, on-missing: keep}'                 | OTHER
            OTHER  | '{table: {ACTIVE: A}, on-missing: "?"}'                  | ?
            active | '{table: {ACTIVE: A}, on-missing: UNKNOWN}'              | UNKNOWN
            """)
        void translates_through_the_table(String in, String params, String out) {
            assertThat(factory.compile(params(params)).format(in)).isEqualTo(out);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            no table                    | '{}'                                           | table is required
            a list for a table          | '{table: [A, B]}'                              | table must be a mapping
            an empty table              | '{table: {}}'                                  | table must not be empty
            a number as a value         | '{table: {Y: 1}}'                              | table.Y must be text (quote it)
            on-missing null             | '{table: {Y: A}, on-missing: null}'            | on-missing: null is not offered
            on-missing empty            | '{table: {Y: A}, on-missing: ""}'              | on-missing: an empty literal is not offered
            an unknown parameter        | '{table: {Y: A}, default: X}'                  | unknown parameter 'default'
            """)
        void refuses_at_apply(String why, String params, String message) {
            assertThatThrownBy(() -> factory.compile(params(params)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        }

        @Test
        void on_missing_error_fails_the_run_without_the_value() {
            ColumnFormatter f = factory.compile(params("{table: {ACTIVE: A}, on-missing: error}"));
            assertThatThrownBy(() -> f.format("SECRET-VALUE"))
                .isInstanceOf(ColumnFormatException.class)
                .hasMessageContaining("no entry in table")
                .hasMessageNotContaining("SECRET-VALUE");
        }
    }

    @Nested
    class FormatDate {
        private final ColumnFormatterFactory factory = new FormatDateFormatterFactory();

        @ParameterizedTest(name = "{0} with {1} → {2}")
        @CsvSource(delimiter = '|', textBlock = """
            2019-12-31                       | '{pattern: dd/MM/yyyy}'                          | 31/12/2019
            2004-10-18 23:42:39+00           | '{pattern: dd/MM/yyyy HH:mm}'                    | 18/10/2004 23:42
            2004-10-18 23:42:39+00           | '{pattern: dd/MM/yyyy HH:mm, zone: Europe/Paris}'| 19/10/2004 01:42
            2026-09-06 18:10:17.899174+05:30 | '{pattern: yyyy-MM-dd HH:mm:ss}'                 | 2026-09-06 12:40:17
            2026-08-01T14:30:00+02:00        | '{pattern: yyyyMMddHHmmss}'                      | 20260801123000
            2026-08-01T14:30:00Z             | '{pattern: yyyyMMdd}'                            | 20260801
            2026-08-01 14:30:00              | '{pattern: HH:mm, zone: Europe/Paris}'           | 14:30
            2019-12-31                       | '{pattern: dd MMM yyyy}'                         | 31 Dec 2019
            ' 2019-12-31 '                   | '{pattern: dd/MM/yyyy}'                          | 31/12/2019
            1900-01-01 00:00:00+00:09:21     | '{pattern: yyyy-MM-dd HH:mm:ss}'                 | 1899-12-31 23:50:39
            """)
        void writes_the_partner_shape(String in, String params, String out) {
            assertThat(factory.compile(params(params)).format(in)).isEqualTo(out);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            no pattern            | '{}'                                   | pattern is required
            a broken pattern      | '{pattern: "dd/MM/yyyy {"}'            | pattern:
            an unknown zone       | '{pattern: dd/MM/yyyy, zone: Mars/X}'  | zone:
            an unknown parameter  | '{pattern: dd/MM/yyyy, locale: fr}'    | unknown parameter 'locale'
            """)
        void refuses_at_apply(String why, String params, String message) {
            assertThatThrownBy(() -> factory.compile(params(params)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            not a date                     | 31/12/2019 | '{pattern: dd/MM/yyyy}' | not a date or a timestamp
            a day the month does not have  | 2019-02-30 | '{pattern: dd/MM/yyyy}' | not a date or a timestamp
            an hour past the day           | 2019-02-28 24:00:00 | '{pattern: dd/MM/yyyy}' | not a date or a timestamp
            a time pattern over a date     | 2019-12-31 | '{pattern: HH:mm}'      | the pattern needs a field the value does not carry
            a zone pattern over a date     | 2019-12-31 | '{pattern: dd/MM/yyyy VV}' | the pattern needs a field the value does not carry
            """)
        void a_value_it_cannot_write_fails_the_run_without_the_value(
                String why, String in, String params, String message) {
            ColumnFormatter f = factory.compile(params(params));
            assertThatThrownBy(() -> f.format(in))
                .isInstanceOf(ColumnFormatException.class)
                .hasMessageContaining(message)
                .hasMessageNotContaining(in);
        }
    }

    @Nested
    class FormatNumber {
        private final ColumnFormatterFactory factory = new FormatNumberFormatterFactory();

        @ParameterizedTest(name = "{0} with {1} → {2}")
        @CsvSource(delimiter = '|', textBlock = """
            1234.5600  | '{}'                                    | 1234.5600
            1234.5600  | '{decimal-separator: ","}'              | 1234,5600
            1234.5650  | '{decimal-separator: ",", scale: 2}'    | 1234,57
            -1234.5    | '{scale: 0}'                            | -1235
            1E+3       | '{}'                                    | 1000
            42         | '{scale: 2}'                            | 42.00
            """)
        void writes_plain_digits(String in, String params, String out) {
            assertThat(factory.compile(params(params)).format(in)).isEqualTo(out);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            a separator that is neither  | '{decimal-separator: ";"}' | decimal-separator must be "." or ","
            a negative scale             | '{scale: -1}'              | scale must be an integer from 0 to 18
            a text scale                 | '{scale: two}'             | scale must be an integer from 0 to 18
            an unknown parameter         | '{grouping: true}'         | unknown parameter 'grouping'
            """)
        void refuses_at_apply(String why, String params, String message) {
            assertThatThrownBy(() -> factory.compile(params(params)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        }

        /** A source value's exponent must not decide how many digits the hub writes: 1E+999999999
         *  in plain digits is a gigabyte string. Refused before toPlainString() builds it. */
        @ParameterizedTest(name = "{0}")
        @CsvSource({"1E+999999999", "1E-999999999", "1E+1000", "1E-1001"})
        void a_number_too_large_for_plain_digits_fails_the_run_without_building_it(String in) {
            ColumnFormatter f = factory.compile(params("{}"));
            assertThatThrownBy(() -> f.format(in))
                .isInstanceOf(ColumnFormatException.class)
                .hasMessageContaining("too large to write as plain digits")
                .hasMessageNotContaining(in);
        }

        @Test
        void the_largest_plain_number_still_writes() {
            assertThat(factory.compile(params("{}")).format("1E+999")).hasSize(1000);
        }

        @Test
        void a_value_that_is_not_a_number_fails_the_run_without_the_value() {
            ColumnFormatter f = factory.compile(params("{}"));
            assertThatThrownBy(() -> f.format("12abc"))
                .isInstanceOf(ColumnFormatException.class)
                .hasMessageContaining("not a decimal number")
                .hasMessageNotContaining("12abc");
        }
    }

    @Nested
    class Mask {
        private final ColumnFormatterFactory factory = new MaskFormatterFactory();

        @ParameterizedTest(name = "{0} with {1} → {2}")
        @CsvSource(delimiter = '|', textBlock = """
            4556123498761234      | '{keep-last: 4}'                       | ************1234
            contact@example.com   | '{keep-first: 2, keep-last: 4}'        | co*************.com
            0612345678            | '{keep-last: 2, mask-char: X}'         | XXXXXXXX78
            1234                  | '{keep-last: 4}'                       | ****
            12                    | '{keep-first: 1, keep-last: 1}'        | **
            Zoë                   | '{}'                                   | ***
            """)
        void masks_the_middle_and_fails_closed_on_a_short_value(String in, String params, String out) {
            assertThat(factory.compile(params(params)).format(in)).isEqualTo(out);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            a negative keep      | '{keep-last: -1}'         | keep-last must be a non-negative integer
            a two-character mask | '{mask-char: "**"}'       | mask-char must be exactly one character
            an unknown parameter | '{columns: [a]}'          | unknown parameter 'columns'
            """)
        void refuses_at_apply(String why, String params, String message) {
            assertThatThrownBy(() -> factory.compile(params(params)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        }
    }
}

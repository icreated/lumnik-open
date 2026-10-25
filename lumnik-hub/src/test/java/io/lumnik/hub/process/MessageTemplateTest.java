package io.lumnik.hub.process;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplateTest {

    @Test
    void extractsPlaceholders_deduplicated_inOrder() {
        assertThat(MessageTemplate.placeholders("no placeholders here")).isEmpty();
        assertThat(MessageTemplate.placeholders("Nouveau client {cname} à {ville}"))
            .containsExactly("cname", "ville");
        assertThat(MessageTemplate.placeholders("{a} then {b} then {a} again"))
            .containsExactly("a", "b");
    }

    @Test
    void nonMatchingBracesAreNotPlaceholders() {
        // {}, {a-b}, a lone { — none matches \{[A-Za-z0-9_]+\}
        assertThat(MessageTemplate.placeholders("empty {} dash {a-b} lone { brace")).isEmpty();
    }

    @Test
    void rendersSubstitution_nullAsEmpty_nonStringViaValueOf() {
        Map<String, Object> row = new HashMap<>();
        row.put("cname", "Claire Martin");
        row.put("ville", null);
        row.put("code", 42);
        assertThat(MessageTemplate.render("Nouveau client {cname} à {ville}", row))
            .isEqualTo("Nouveau client Claire Martin à ");
        assertThat(MessageTemplate.render("code={code}", row)).isEqualTo("code=42");
        // a repeated placeholder renders every occurrence
        assertThat(MessageTemplate.render("{cname} / {cname}", row))
            .isEqualTo("Claire Martin / Claire Martin");
    }

    /** #1303: a timestamp renders as {@code TemporalText.render} shows it, tested there. */
    /** #1357: a numeric column is stored at the table's scale, NUMERIC(18,4); the message shows the number. */
    @Test
    void rendersANumericWithoutTheTablesTrailingZeros() {
        Map<String, Object> row = Map.of("kw", new BigDecimal("18.7000"), "cap", new BigDecimal("10.0000"),
                "zero", new BigDecimal("0.0000"), "big", new BigDecimal("1200.0000"));
        assertThat(MessageTemplate.render("{kw} kW, cap {cap}, {zero}, {big}", row))
                .isEqualTo("18.7 kW, cap 10, 0, 1200");
    }

    @Test
    void rendersATimestampAsItsInstant() {
        Map<String, Object> row = Map.of("at", Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
        assertThat(MessageTemplate.render("{at}", row)).isEqualTo("2026-07-01T00:00:00Z");
    }

    @Test
    void nonMatchingBracesPassThroughLiteral_andReplacementSpecialsAreSafe() {
        Map<String, Object> row = Map.of("v", "a$1\\b");  // $ and \ are regex-replacement specials
        assertThat(MessageTemplate.render("keep {} literal, sub {v}", row))
            .isEqualTo("keep {} literal, sub a$1\\b");
    }
}

package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The single-slot Pattern cache must follow the spec's pattern and keep the refusals' wording. */
class RegexReplaceHookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RegexReplaceHook hook = new RegexReplaceHook();

    private String run(String pattern, String replacement, String cell) throws Exception {
        JsonNode params = JSON.readTree(JSON.writeValueAsString(
            Map.of("column", "c", "pattern", pattern, "replacement", replacement)));
        Map<String, Object> row = new HashMap<>(Map.of("c", cell));
        hook.apply(row, params, new MappingState());
        return (String) row.get("c");
    }

    @Test
    void followsThePatternWhenTwoSpecsAlternate() throws Exception {
        assertThat(run("\\s+", "", "a b c")).isEqualTo("abc");
        assertThat(run("[0-9]", "#", "a1b2")).isEqualTo("a#b#");
        assertThat(run("\\s+", "", "x y")).isEqualTo("xy");
        assertThat(run("\\s+", "_", "x y")).isEqualTo("x_y");   // same pattern, new replacement
    }

    @Test
    void anInvalidPatternIsRefusedByNameEveryTime() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> run("(", "", "a"))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining("regex-replace hook: 'pattern' is not a valid regex");
        }
    }

    @Test
    void aLoneDollarInTheReplacementIsRefusedAsAReplacement() {
        assertThatThrownBy(() -> run("a", "$", "a"))
            .isInstanceOf(MappingValidationException.class)
            .hasMessageContaining("'replacement' is not usable with this pattern");
    }
}

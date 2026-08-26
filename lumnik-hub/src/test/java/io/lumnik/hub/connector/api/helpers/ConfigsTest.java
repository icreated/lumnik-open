package io.lumnik.hub.connector.api.helpers;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigsTest {
    @Test void requireString_returnsValue_orThrows() {
        assertThat(Configs.requireString(Map.of("k", "v"), "k")).isEqualTo("v");
        assertThatThrownBy(() -> Configs.requireString(Map.of(), "k"))
            .isInstanceOf(ConfigValidationException.class).hasMessageContaining("k");
        Map<String,Object> blank = new HashMap<>(); blank.put("k", "  ");
        assertThatThrownBy(() -> Configs.requireString(blank, "k")).isInstanceOf(ConfigValidationException.class);
    }
    @Test void string_isNullAndNonStringSafe() {
        Map<String,Object> m = new HashMap<>(); m.put("k", 5); m.put("n", null); m.put("blank", "  ");
        assertThat(Configs.string(m, "k")).isEmpty();      // non-string → empty
        assertThat(Configs.string(m, "n")).isEmpty();      // null → empty
        assertThat(Configs.string(m, "blank")).isEmpty();  // blank → empty
        assertThat(Configs.string(m, "missing")).isEmpty();
        assertThat(Configs.string(Map.of("k","v"), "k")).contains("v");
    }
}

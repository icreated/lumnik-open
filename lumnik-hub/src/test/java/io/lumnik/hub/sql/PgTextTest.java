package io.lumnik.hub.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule {@link PgText} states, tested where it lives; {@code WebhookConsumerIT} proves the stall it ends (#943). */
class PgTextTest {

    @Test
    void everyNulBecomesTheReplacementCharacter() {
        assertThat(PgText.storable("\u0000x\u0000\u0000y\u0000")).isEqualTo("\uFFFDx\uFFFD\uFFFDy\uFFFD");
    }

    @Test
    void textWithoutNulIsUnchanged() {
        String remote = "HTTP 500: {\"error\":\"d\u00e9j\u00e0 vu\"} \uFFFD";

        assertThat(PgText.storable(remote)).isEqualTo(remote);
        assertThat(PgText.storable(null)).isNull();
    }

    @Test
    void aNulInsideAJsonTreeIsReplacedWhereverItSits() throws Exception {
        var mapper = new ObjectMapper();
        var tree = mapper.readTree("{\"a\":\"x\\u0000y\",\"b\":[\"\\u0000\",1],\"c\":{\"d\":\"ok\"}}");

        var stored = PgText.storableJson(tree);

        assertThat(mapper.writeValueAsString(stored)).doesNotContain("\\u0000");
        assertThat(stored.path("a").asText()).isEqualTo("x\uFFFDy");
        assertThat(stored.path("b").path(0).asText()).isEqualTo("\uFFFD");
        assertThat(stored.path("b").path(1).asInt()).isEqualTo(1);
        assertThat(stored.path("c").path("d").asText()).isEqualTo("ok");
        assertThat(tree.path("a").asText()).isEqualTo("x\u0000y");   // the argument is left as it was
        assertThat(PgText.storableJson(null)).isNull();
    }

    @Test
    void twoFieldNamesThatSanitiseAlikeBothSurvive() throws Exception {
        var mapper = new ObjectMapper();
        var tree = mapper.createObjectNode().put("a\u0000", 1).put("a\uFFFD", 2);

        var stored = PgText.storableJson(tree);

        assertThat(stored.size()).as("no field is lost to the other").isEqualTo(2);
        assertThat(stored.properties().stream().map(e -> e.getValue().asInt())).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void manyFieldNamesThatSanitiseAlikeCostLinearTimeAndAllSurvive() {
        var mapper = new ObjectMapper();
        var alike = mapper.createObjectNode();
        for (int bits = 0; bits < 4096; bits++) {
            StringBuilder name = new StringBuilder();
            for (int b = 0; b < 12; b++) name.append((bits >> b & 1) == 0 ? '\u0000' : '\uFFFD');
            alike.put(name.toString(), bits);
        }

        var stored = PgText.storableJson(alike);

        assertThat(stored.size()).isEqualTo(4096);
    }
}

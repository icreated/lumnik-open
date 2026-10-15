package io.lumnik.hub.sql;

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
}

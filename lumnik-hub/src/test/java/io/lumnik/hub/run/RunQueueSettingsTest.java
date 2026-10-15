package io.lumnik.hub.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunQueueSettingsTest {

    @Test
    void a_zero_or_negative_max_age_stops_the_hub_instead_of_expiring_every_row() {
        RunQueueSettings s = new RunQueueSettings();
        s.maxAge = Duration.ZERO;
        assertThatThrownBy(s::validate).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lumnik.run-queue.max-age");
        s.maxAge = Duration.ofSeconds(-1);
        assertThatThrownBy(s::validate).isInstanceOf(IllegalStateException.class);
        s.maxAge = Duration.ofHours(1);
        s.validate();   // no throw
    }
}

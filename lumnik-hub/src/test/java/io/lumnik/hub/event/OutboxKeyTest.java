package io.lumnik.hub.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxKeyTest {

    @Test
    void aKeyThatFitsIsKept_aLongerOneIsAStableDigest() {
        String fits = "k".repeat(OutboxKey.MAX);
        String longer = "k".repeat(OutboxKey.MAX + 1);
        assertThat(OutboxKey.bounded(fits)).isEqualTo(fits);
        assertThat(OutboxKey.bounded(longer)).startsWith("sha256:").hasSize(7 + 64)
            .isEqualTo(OutboxKey.bounded(longer));
        assertThat(OutboxKey.bounded(null)).isNull();
    }
}

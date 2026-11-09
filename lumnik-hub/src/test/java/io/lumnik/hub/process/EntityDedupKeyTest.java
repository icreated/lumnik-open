package io.lumnik.hub.process;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #1833: one decision per process, source event and fused row — the key that makes a replay harmless. */
class EntityDedupKeyTest {

    @Test
    void sameProcessEventAndRowGiveTheSameKey() {
        assertThat(ProcessConsumer.entityDedupKey("gold", 7, "1|3:C-1")).isEqualTo(ProcessConsumer.entityDedupKey("gold", 7, "1|3:C-1"));
    }

    @Test
    void anotherRowAnotherEventOrAnotherProcessGivesAnotherKey() {
        String base = ProcessConsumer.entityDedupKey("gold", 7, "1|3:C-1");
        assertThat(ProcessConsumer.entityDedupKey("gold", 7, "1|3:C-2")).isNotEqualTo(base);   // a lookup would reach many rows
        assertThat(ProcessConsumer.entityDedupKey("gold", 8, "1|3:C-1")).isNotEqualTo(base);   // a later physical change decides again
        assertThat(ProcessConsumer.entityDedupKey("silver", 7, "1|3:C-1")).isNotEqualTo(base);
    }

    /** The column is VARCHAR(200): a long key must not turn a decision into a 22001. */
    @Test
    void aLongKeyStaysWithinTheColumn_andStaysDistinct() {
        String a = ProcessConsumer.entityDedupKey("gold", 7, "1|400:" + "a".repeat(400));
        String b = ProcessConsumer.entityDedupKey("gold", 7, "1|400:" + "a".repeat(399) + "b");
        assertThat(a).hasSizeLessThanOrEqualTo(200).isNotEqualTo(b);
        assertThat(b).hasSizeLessThanOrEqualTo(200);
    }
}

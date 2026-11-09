package io.lumnik.hub.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.event.EventDeadLetters;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.RowEventGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #1837: a lookup change queues one fan-out per process. When queuing one fails (after its own retries), that process
 * leaves a letter of its own and the others are still queued; during a replay the failure goes out instead.
 */
class ProcessConsumerLookupFailureTest {

    private final List<String> queued = new ArrayList<>();
    private final List<String> letters = new ArrayList<>();
    private final ProcessConsumer consumer = new ProcessConsumer();

    ProcessConsumerLookupFailureTest() {
        consumer.fused = new FusedRowReader() {
            @Override Optional<FusedKey> key(EventOutboxRow row, List<String> keyExprs, String hashColumn, Long rowId) {
                return Optional.of(new FusedKey(List.of("K1")));
            }
        };
        consumer.fanOut = new LookupFanOut() {
            @Override void enqueue(long tenantId, String process, long eventId, String target, String lookupKey) {
                if (process.equals("bad")) throw new IllegalStateException("queue down");
                queued.add(process);
            }
        };
        consumer.deadLetters = new EventDeadLetters() {
            @Override public void record(String consumer, long eventId, String targetKey, Throwable cause) {
                letters.add(consumer + "/" + targetKey);
            }
        };
    }

    private static ProcessConsumer.Rule rule(String name) {
        return new ProcessConsumer.Rule(
            new ProcessManifestStore.StoredProcess(name, null, "connector.t_l", List.of(), "decision." + name, "m", List.of(), null, true),
            List.of());
    }

    private ProcessConsumer.LookupListening listening() {
        LookupReach reach = new LookupReach("e:s:t_l:a>b", "connector.t_l", "connector.t_s", List.of("k"), "a", "b", "text");
        return new ProcessConsumer.LookupListening(List.of(reach), List.of(rule("first"), rule("bad"), rule("last")));
    }

    private static EventOutboxRow lookupChanged() {
        return EventOutboxRow.restored(Map.of("id", 7, "tenant_id", 1, "event_type", RowEventGate.DATA_ROW_CHANGED,
            "source", "connector:test", "entity", "connector.t_l", "entity_key", "hash", "payload", Map.of(),
            "occurred_at", "2026-11-06T00:00:00+00:00", "created_at", "2026-11-06T00:00:00+00:00"));
    }

    @Test
    void aProcessThatCannotBeQueuedLeavesItsOwnLetterAndTheOthersAreQueued() {
        consumer.onLookup(lookupChanged(), "_row_hash", null, listening(), consumer.toOutbox);

        assertThat(queued).containsExactly("first", "last");
        assertThat(letters).containsExactly("process/process:bad");
    }

    @Test
    void duringAReplayTheFailureGoesOut() {
        assertThatThrownBy(() -> consumer.onLookup(lookupChanged(), "_row_hash", null, listening(), consumer.replayOutbox))
            .isInstanceOf(IllegalStateException.class);
        assertThat(letters).isEmpty();
    }
}
